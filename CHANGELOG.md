# Changelog

Notable changes to the LarkBatis core repository. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section for the version being tagged out of this
file and uses it verbatim as the GitHub Release body, so a version with no
section here does not get released.

## [Unreleased]

### Added

- **Custom type handlers.** A value whose type the built-in codec has no
  strategy for now moves through a
  `io.github.larkbatis.runtime.LarkBatisTypeHandler<J>` — two methods,
  `read(ResultSet, int)` and `write(PreparedStatement, int, J)`, named
  explicitly and called directly from generated code. There is still no
  registry, no `(Type, JdbcType)` lookup and no discovery scan: which handler
  runs is decided during `javac`, and one instance per generated type is held
  in a `static final` field typed with the handler's own class, so the call
  site stays monomorphic.

  A handler can be named at three sites, and all three are read at build time:

  - `@Handler(MoneyHandler.class)` on a result property — the field, the getter
    or the setter, as with `@Column`; two of them disagreeing is an error.
  - `@Handler(...)` on a mapper method parameter.
  - `typeHandler="..."` in mapper XML, on `<id>`/`<result>` or inside a `#{}`.
    This is the form a migrated MyBatis mapper already carries, so the bean it
    maps onto needs no annotation at all.

  A handler also lifts the type whitelist for the value it moves: a property or
  parameter typed `Money` compiles once a handler names it.

- **Six scanner rules for things the mappers do not show you.** Every one of
  these stopped the build on the first statement that touched it while
  `larkbatisScan` reported the codebase clean — which is the one answer a
  migration-cost tool must never give.

  - `result property outside the type whitelist`. The scan now reads the
    property types of every class the mappers name as a `resultType`, `type`,
    `javaType` or `ofType`. This is the rule that mattered most: a result class
    is an ordinary POJO that mentions MyBatis nowhere, so the Java scan skipped
    it, and one `java.util.Date` field in a shared DTO is a blocker for every
    statement that returns it. A blocklist rather than the generator's
    whitelist, because a textual scan cannot tell an enum or a nested bean —
    both supported — from a class it has never heard of, and inventing blockers
    is worse than the under-reporting this tool already declares.
  - `Map or Object result type`. `resultType="map"` had a blocker's severity
    waiting for it and no rule to raise it; the map-like test was applied to
    `parameterType` only.
  - `#{} more than one property deep`. Three segments is the threshold, because
    two are ambiguous: `#{u.name}` is one hop against a bean and one hop
    against `@Param("u")`.
  - `overloaded mapper method` and `mapper interface extends another
    interface` — the two mapper shapes that produce a generated class that does
    not compile.
  - `typeHandler= inside #{}`, counted apart from the attribute form because it
    sits in SQL text and is easy to miss when costing the handler rewrite.

  Measured against the `mybatis-3` corpus (505 files, 575 statements): 25 map
  result types, 5 overloads, 5 inheriting mappers, 1 unsupported property,
  1 deep path, 51 inline handlers.

- **The type whitelist covers the JDBC and `java.util` date types.**
  `java.util.Date`, `java.sql.Date`/`Time`/`Timestamp`, `BigInteger`,
  `char`/`Character`, and `java.time`'s `OffsetDateTime`, `OffsetTime` and
  `ZonedDateTime` now move across JDBC with no handler needed. Each matches its
  MyBatis counterpart: a `java.util.Date` is read as a `Timestamp` and narrowed
  (`DateTypeHandler`), a `Character` reads empty as null
  (`CharacterTypeHandler`), a `BigInteger` travels as a `BigDecimal`
  (`BigIntegerTypeHandler`), and the offset-aware types go through
  `getObject`/`setObject`, which is the only route that preserves an offset.

  Refusing these was the single biggest thing standing between a legacy
  codebase and a first successful compile: they appear in nearly every DTO of a
  certain age, and the migration began with "rewrite every DTO" before a mapper
  could even be tried. Moving a property to `java.time` is still the better end
  state; it is no longer a precondition. `java.util.UUID` is deliberately still
  out — MyBatis ships no UUID handler either, so there is no behaviour to match
  and `@Handler` is the honest answer.

  One place LarkBatis takes a position MyBatis does not: a primitive `char`
  reading a NULL column gives `' '`, the same bargain `rs.getInt` makes with 0,
  where MyBatis hands a null `Character` to a primitive setter and throws an NPE
  that names nothing.

### Fixed

- **`#{prop, typeHandler=...}` is no longer dropped in silence.** The
  attributes inside a `#{}` were parsed off and discarded, so a migrated
  statement carrying an inline handler compiled clean and bound through the
  built-in codec instead — an ordinal enum column read back as a name, and
  nothing said so. `typeHandler` is now read; `jdbcType` and `javaType` stay
  accepted-and-ignored, those being build-time facts the generator already has.
- **An unknown attribute on `<association>`/`<collection>` is now an error.**
  Only a few names were checked by hand, so anything else — `typeHandler`,
  `fetchType`, `column` — was accepted and never read.
- **A `Stream`-returning mapper method no longer leaks its Connection when a
  bind throws.** This is the one generated shape that cannot have a `finally`
  — the Connection, statement and cursor are handed to the stream — and its
  only failure arm caught `SQLException`. Binding `#{user.name}` against a
  null bean throws an NPE instead, which carried all three out of the method
  with nothing left to release them. `LarkBatisSession.streamFailed` gained a
  `RuntimeException` overload and the generated body a second `catch` arm;
  every other shape already released from a `finally`.
- **`useGeneratedKeys` now throws when the driver returns no key.**
  `LarkBatisNoKeyException` was documented and defined but never thrown: the
  single-row path assigned inside `if (gk.next())` and did nothing otherwise,
  leaving `keyProperty` at `0` for the caller to discover at the next foreign
  key. The batch path has always refused a key count that does not match.
  `INSERT ... ON DUPLICATE KEY UPDATE` landing on the update branch is how
  this reaches production.
- **A `<foreach>` over a bean property reads the getter once.** The
  placeholder loop called `q.getIds().size()` and the bind loop iterated
  `q.getIds()` again — two calls that must agree on a count, with nothing
  making them. The collection is now read into a local both passes share. A
  `<foreach>` over a mapper parameter is unchanged: a parameter is already a
  local.
- **`Stream` no longer claims `NONNULL`.** A `Stream<String>` over a nullable
  column yields `null` for a NULL row, and a downstream operator is entitled
  to believe the spliterator's characteristics.
- **`databaseId` is documented as unsupported.** The feature table marked it
  supported and three more pages listed it among the inputs resolved at
  runtime, while the frontend rejects the attribute outright. The design did
  reserve the slot; nothing implements it, and it is now described as
  reserved.

### Changed

- **`larkbatisScan` no longer reports "compiles as-is" for statements that do
  not compile.** Two under-reports, both optimistic in the direction that
  matters most to someone deciding whether to migrate:

  - A file the frontend rejects outright is a finding about the *file*, so it
    carries no line number and the per-statement verdict skipped it — a
    three-statement mapper that does not parse reported "3, 100.0% compiles
    as-is" directly beneath its own BLOCKER. Whole-file findings are now
    charged to every statement in the file.
  - The scanner's grammar check is syntax-only and accepted any method call,
    so `name != null and name.trim() != ''` — the most common `<if test>` in
    MyBatis — scanned clean and then failed the build. Calls that answer with
    a value on every type that has them are reported as a mechanical edit;
    calls whose return type decides it are reported as a decision; `is`/`has`/
    `can`/`should` prefixes are trusted as boolean getters so the common case
    stays quiet.

- **`larkbatisScan` counts type aliases.** A codebase whose `<typeAliases>`
  block scans its model package needs a fully-qualified name written into
  nearly every statement, and scanned almost clean because
  `collectResultClasses` deliberately skips names without a dot — resolving
  them would take a compiler, and a migration report naming the wrong class is
  worse than one that declines to guess. Counting needs no resolution:
  `TYPE_ALIAS_DECLARED` for each declaration and `<package>` scan,
  `UNQUALIFIED_TYPE_NAME` for each `resultType`, `parameterType`, `type`,
  `javaType` or `ofType` that is not fully qualified. MyBatis's own aliases
  for JDK types are excluded — `resultType="long"` costs nothing, because the
  return type comes off the mapper method.

## [0.1.0] - 2026-08-30

First public release: an ahead-of-time MyBatis. Everything derivable from the
*shape* of a mapper — the SQL text, parameter positions, column-to-setter
mapping, the dynamic-SQL tree — is resolved during `javac`. What ships at
runtime is generated plain Java plus a thin JDBC layer with no reflection, no
proxies, no OGNL and no dependencies beyond the JDK.

### Artifacts

| Coordinate | Scope |
|---|---|
| `io.github.larkbatis:larkbatis-annotations` | runtime — mapper annotations, no logic |
| `io.github.larkbatis:larkbatis-runtime` | runtime — `LarkBatisSession`, `LarkBatisTx`, `JdbcCodec`, `SqlFragment`, `LarkBatisSql` |
| `io.github.larkbatis:larkbatis-processor` | build-only — the annotation processor. Never belongs on a runtime classpath |
| `io.github.larkbatis:larkbatis-scanner` | build-only — `larkbatis-scan`, the migration-cost report |

`larkbatis-scan` is also attached to this release as a standalone zip, so the
migration report can be run on a codebase that has never been built.

### Added

- **Mapper compilation.** `@Select` / `@Insert` / `@Update` / `@Delete` on an
  interface, or `@Mapper` plus a mapper XML whose `namespace` names it, compile
  into one `<Mapper>$$Impl` per interface, one row reader per result class, and
  a static `LarkBatisMappers` registry. The generated code is ordinary,
  readable, breakpoint-able JDBC — that is a feature, not a by-product.
- **`#{}` binding** resolved at build time against the method signature.
  Parameter names come from the AST, or from `@Param`.
- **Row readers.** Positional (`rs.getLong(1)`) whenever the generator can parse
  the select list; otherwise name-based, with the indexes resolved once from
  `ResultSetMetaData` on the first row. Which one a statement got is reported at
  build time.
- **Dynamic SQL** — `<if>`, `<choose>/<when>/<otherwise>`, `<where>`, `<set>`,
  `<trim>`, `<sql>`/`<include>` — lowered into condition locals and guarded
  appends. Each `test` is evaluated once into a `boolean` that both the SQL
  assembly and the parameter binding read.
- **A narrow `test` grammar** in place of OGNL: null checks, comparisons on
  statically typed property paths, `and`/`or`/`not`, `size()`/`length()`/
  `isEmpty()`, and boolean-returning methods. MyBatis truthiness is deliberately
  not reproduced — `test="count"` is a compile error asking for `count != 0`.
- **`<foreach>`** over statically typed collections, arrays and `Map<K,V>`,
  compiled to two loops over the same elements: one appends placeholders, one
  binds values. Loops nest. An empty collection throws
  `LarkBatisEmptyForeachException` naming the parameter rather than sending
  `WHERE id IN ()` to the database.
- **`@PadPow2`** rounds an `IN` list's placeholder count up to the next power of
  two, which bounds how many distinct SQL texts one statement can produce.
- **`<resultMap>` with one level of nesting**, collapsed by a join with the
  ResultSet ordered by the parent key — no second query, no `N+1`.
- **`Stream` returns.** The only generated shape without a `finally`: the caller
  owns the cursor and closes it.
- **Transactions.** `LarkBatisTx` with vote-to-commit semantics — an inner
  scope that leaves without voting poisons the transaction, and the outer commit
  throws `LarkBatisRollbackOnlyException` rather than silently rolling back
  something that looked like success.
- **`useGeneratedKeys`** passes explicit key column names to
  `prepareStatement(sql, String[])`, because Oracle returns `ROWID` and
  PostgreSQL returns every column under `RETURN_GENERATED_KEYS`. Batch mode
  verifies the returned key count.
- **`${}` discipline.** A `${}` parameter binds only to `SqlFragment`, a closed
  value type (`int`, `long`, `boolean`, an enum) or a parameter annotated
  `@OrderBy(allowed = {...})`. A `String` there is a compile error.
  `SqlFragment.unsafeRawSql` is the single audit point for arbitrary SQL text,
  and statements containing `${}` get a generated `LarkBatisSql.trackVariants`
  call so an unbounded fragment shows up as a bounded number of SQL texts rather
  than an unbounded statement cache.
- **Java Platform Module System.** `larkbatis-annotations` and
  `larkbatis-runtime` ship real named modules. A modular consumer needs
  `requires io.github.larkbatis.runtime`,
  `requires static io.github.larkbatis.annotations` and — the one that
  surprises people — `requires static java.compiler`, because every emitted
  source carries `@Generated`. The build-only modules ship no descriptor, by
  design.
- **`larkbatis-scan`**, which reads an existing MyBatis codebase and prints
  what migrating it would cost, statement by statement with line numbers, ranked
  by how much thought each finding needs. It compiles nothing and resolves no
  dependencies. Every finding names a heading in `MIGRATION.md`, and it shares
  its frontend with the processor, so the report cannot drift away from what
  actually compiles.
- **`BENCHMARKS.md`** — a JMH suite, not a hand loop, with the method stated.
  Reading 10 000 rows of 12 columns: **3.38 ms → 0.54 ms (−84%)** and
  **10.2 MB → 1.88 MB (−82%)**. Cold start to first row: **61.8 ms → 6.3 ms**.
  A single-row lookup over loopback TCP: **−5%**, which is the honest half —
  with a real round trip in the way the mapper layer stops mattering, and the
  suite says so.

### Deliberately not supported

Dropped: OGNL, `<bind>`, the `@SelectProvider` family, lazy loading,
plugins/interceptors, `Object`/`Map` parameters, `<discriminator>`, nested
selects in `<collection>`, the second-level cache, runtime `addMapper()`, and
`RowBounds`. Narrowed: `<where>`/`<set>`/`<trim>` take literal attributes and
are folded at build time; `<sql>`/`<include>` needs a static `refid`; custom
TypeHandlers are named explicitly with `@Handler`, never discovered.

`MIGRATION.md` covers each of these and what to write instead.

### Known limitations

- **`@Handler` is declared but not yet read by the processor.** It compiles and
  it is part of the intended API; today it has no effect. *(Implemented after
  0.1.0 — see Unreleased.)*
- **`useGeneratedKeys` without `keyColumn` does not fail.** It falls back to
  `RETURN_GENERATED_KEYS` with a mandatory build warning. Treat that warning as
  an error on Oracle and PostgreSQL.
- **No native image has been built.** The absence of reflection is structural
  and can be checked by reading the generated code, but the GraalVM result
  itself is unverified — there was no GraalVM on the machine this was developed
  on.
- **javac only.** The processor relies on javac behaviour (declaration order of
  elements, multi-round resolution of generated types). ECJ is not supported.
- **Test-scoped mappers are not wired.** Mapper interfaces belong in
  `src/main/java`; test code uses them as ordinary classes.
- Under Gradle, compile with `-parameters` or name every parameter with
  `@Param`. Incremental builds re-run aggregating processors over unchanged
  mappers from their class files, where parameter names survive only with that
  flag.
- Declare `larkbatis-processor` **after** `org.projectlombok:lombok` on
  `annotationProcessor`. javac runs discovered processors in classpath order,
  and declared first, LarkBatis sees a result class with no accessors.

[Unreleased]: https://github.com/larkbatis/larkbatis/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/larkbatis/larkbatis/releases/tag/v0.1.0
