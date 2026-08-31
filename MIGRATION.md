# Migrating a MyBatis codebase to LarkBatis

LarkBatis keeps MyBatis's mapper model — the same XML, the same `#{}`, the
same annotations — and removes the interpreter underneath it. Statements are
compiled at build time into plain Java you can open and read. That trade buys
zero reflection, zero runtime dependencies and a working GraalVM native image;
it costs you the features that only exist because there *was* an interpreter.

This guide is the reference the scanner points at. Every finding it prints
names a heading below.

---

## Step 1 — find out what it would cost

Before changing anything, run the scanner over the codebase. It compiles
nothing and resolves no dependencies, so it works on a checkout that has never
been built:

```bash
larkbatis-scan /path/to/your-service
```

It reads every mapper XML, every `mybatis-config.xml` and every Java file that
mentions `org.apache.ibatis`, then answers the only question that matters
first:

```
Verdict — what happens to the 1204 statements
  compiles as-is                    889   73.8%
  needs a mechanical edit           221   18.4%
  needs a decision                   58    4.8%
  blocked on a dropped feature       36    3.0%
```

Useful flags: `--summary` (counts only), `--min=BLOCKER` (only what has no
equivalent), `--limit=N` (findings listed per file), `--out=report.txt`, and
`--fail-on-blocker` for CI.

<a id="reading-this-report"></a>
### Reading the report

Four levels, in the order they cost you time:

| Level | Meaning |
|---|---|
| `BLOCKER` | No LarkBatis equivalent. The mapper has to change. |
| `EDIT` | A rewrite with a known shape — the report tells you what to write. |
| `REVIEW` | Supported, once someone decides how. |
| `INFO` | Compiles as-is; worth knowing before it surprises you. |

Two columns are there to stop a count from lying to you. **`in N files`** tells
you whether 1000 findings are a codebase-wide wall or one generated mapper —
that difference is the difference between a quarter and an afternoon. And the
**"Where it is concentrated"** block names the five files holding most of the
work.

`the frontend rejected the file` means the scanner's own catalogue could not
explain why LarkBatis refused the file. The message printed with it comes
straight from the compiler frontend. If you see many of these, please report
them — they are gaps in this guide, not in your mapper.

---

## Step 2 — wire up the build

Use the build plugin. It knows where your mapper XML lives and passes the
compiler the arguments it needs; doing it by hand is the most common way to
get a confusing first error.

```kotlin
// build.gradle.kts
plugins {
    id("io.github.larkbatis") version "0.1.0-SNAPSHOT"
}

dependencies {
    implementation("io.github.larkbatis:larkbatis-runtime:0.1.0-SNAPSHOT")
    implementation("io.github.larkbatis:larkbatis-annotations:0.1.0-SNAPSHOT")
    annotationProcessor("io.github.larkbatis:larkbatis-processor:0.1.0-SNAPSHOT")
}
```

Spring Boot users add the starter instead of the runtime:

```kotlin
implementation("io.github.larkbatis:larkbatis-spring-boot-starter:0.1.0-SNAPSHOT")
```

Then delete the `mybatis:` block from `application.yml` and change the
annotation imports from `org.apache.ibatis.annotations` to
`io.github.larkbatis.annotations`. In a service whose mappers are all static
SQL with `#{}`, that plus the build file is the entire diff — mapper XML needs
no edits at all, and `parameterType` is accepted and ignored.

### Three things that bite on the first build

**Lombok must run first.** javac runs annotation processors in classpath
order. If LarkBatis is declared before Lombok, it sees your result classes
with no accessors at all, because Lombok has not written them yet. Put Lombok
first:

```kotlin
annotationProcessor("org.projectlombok:lombok")
annotationProcessor("io.github.larkbatis:larkbatis-processor:0.1.0-SNAPSHOT")
```

The error message says so when it sees a `lombok.*` annotation on the class,
but only then.

**`-parameters` is required for incremental builds.** Gradle re-runs
aggregating annotation processors over *unchanged* mappers from their class
files, where parameter names survive only if they were compiled with
`-parameters`. Without it an incremental build sees `arg0`, `arg1` and every
`#{name}` stops resolving. The plugin adds the flag; if you configure javac by
hand, add it yourself, or annotate every parameter with `@Param`.

**javac only.** The processor relies on javac's element ordering and its
multi-round resolution of generated types. ECJ is not supported.

---

## Step 3 — the findings

<a id="raw-sql"></a>
### `${}` and raw SQL

A `String` parameter bound to `${}` is a compile error. This is the single
biggest source of mechanical edits in a migration, and also the point of it:
after the change, `grep -rn unsafeRawSql src/` lists every place arbitrary
text can enter your SQL. MyBatis has no equivalent convergence point.

Pick whichever fits the call site:

| The value is | Declare it as | Call site becomes |
|---|---|---|
| a column or table name | `SqlFragment` | `SqlFragment.identifier(name)` |
| one of a known set | `SqlFragment` | `SqlFragment.allowed(value, "asc", "desc")` |
| a sort column | `@OrderBy(allowed = {"id", "name"}) String` | unchanged — compiled to a switch |
| a number or flag | `int`, `long`, `boolean`, an enum | unchanged |
| genuinely arbitrary | `SqlFragment` | `SqlFragment.unsafeRawSql(text)` |

`identifier()` accepts letters, digits, underscore and one dot; it rejects
everything else rather than escaping it. A rejection is the tool telling you
that call site deserves a human — that is the value, not the friction.

`<include>` with a computed `refid` has no path forward: `refid` is resolved
and inlined at build time, so it must be a literal.

<a id="expressions"></a>
### `<if test="...">` expressions

LarkBatis compiles `test` attributes instead of evaluating them, against a
deliberately small grammar: null checks, comparisons on statically-typed
property paths, `and`/`or`/`not`, `size()`/`length()`/`isEmpty()`,
boolean-returning methods on known types, and bare booleans.

**OGNL truthiness is not reproduced, on purpose.** `test="count"` and
`test="user"` are compile errors:

```xml
<if test="name">          <!-- error: which did you mean? -->
<if test="name != null">  <!-- write this -->
<if test="count != 0">    <!-- or this -->
<if test="list.isEmpty()">
```

In MyBatis, one attribute silently means "not null" for an object, "not zero"
for a number and "not empty" for a string. Choosing for you is exactly the
kind of guess that produces a wrong query instead of an error.

**A call has to answer with a condition, not a value.** This is the one that
shows up in almost every legacy mapper:

```xml
<if test="name != null and name.trim() != ''">   <!-- error -->
<if test="name != null and !name.isEmpty()">     <!-- write this -->
```

`size()`, `length()` and `isEmpty()` are accepted on anything, and so is any
method returning `boolean`. A method returning a `String`, a number or a
collection — `trim()`, `toString()`, `get()` — is refused, because the result
would then need OGNL's truthiness rules to become a condition, and those are
exactly what is not reproduced. Where the trim really matters, do it on the
way in and pass the trimmed value as a parameter.

The scanner reports both halves of this: a call it knows answers with a value
is a mechanical edit, and a call of your own that it cannot type is a
one-line decision — if it already returns `boolean`, there is nothing to do.

Anything outside the grammar — static field references (`@com.foo.Bar@BAZ`),
`in {...}`, index expressions, arithmetic — is a compile error. The fix is
usually to make the decision in Java and pass the result in as a `boolean`
parameter. The grammar is frozen deliberately: extending it once per unusual
mapper is how a small compiler turns back into OGNL.

`<script>` inside a Java annotation is read, but the same grammar applies to
the tests inside it.

<a id="parameters"></a>
### `Map` and `Object` parameters

`parameterType="map"`, `Map<String, Object>` and bare `Object` parameters have
no type for `#{}` to resolve against, so they are rejected. Introduce a
parameter object, or split the map into `@Param` arguments:

```java
List<User> search(@Param("name") String name, @Param("minAge") int minAge);
```

Also note that `#{}` resolution is stricter than MyBatis's: a single scalar
parameter binds under its declared name (or `paramN`), not under any name you
happen to write. Migration is renaming one side.

**One property hop.** `#{user.name}` resolves, and so does `#{u.name}` against
`@Param("u") User u`. `#{order.customer.city}` does not — that is two hops, and
the generator does not walk a path it cannot type at each step. Pass the inner
value as its own parameter, or flatten the parameter object. The scanner
reports any path of three or more segments, which is the depth that is too deep
under either reading of the first one.

<a id="row-readers"></a>
### Result reading and column names

**`mapUnderscoreToCamelCase` is applied at build time, and defaults to on.**
MyBatis defaults it to *off*, so a configuration that never mentioned the
setting still changes behaviour on the way across: columns that used to stay
unmapped start being read.

Two ways to keep the old behaviour. Carry the setting across —

```
-Alarkbatis.mapUnderscoreToCamelCase=false
```

— and the build reports every column that stops reaching a property, naming
the property, so nothing goes quiet. Or leave it on and put `@Column` on the
properties whose columns must not be guessed at.

When the generator can parse the select list, it reads rows positionally
(`rs.getLong(1)`). When it cannot — `SELECT *`, or a `${}` inside the select
list — that one statement falls back to a name-based reader whose indexes are
resolved once from `ResultSetMetaData`. Correct either way, measurably slower
in the second case, and reported at build time so it is never a surprise.

Unmatched columns are ignored, as in MyBatis auto-mapping.

<a id="result-classes"></a>
### Result classes

Result classes need a no-arg constructor and setters. `<constructor>` result
mappings and records are not supported.

Property types come from a whitelist. Anything outside it is a compile error
rather than a silently unmapped field — or a `@Handler` naming the class that
moves it.

| | |
|---|---|
| primitives and their boxes | including `char`/`Character`, carried as a one-character string |
| `String`, `BigDecimal`, `BigInteger`, `byte[]` | |
| enums | stored by name |
| `java.time` | `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime` |
| `java.util.Date` | read as a `Timestamp` and narrowed, exactly as MyBatis's `DateTypeHandler` does |
| `java.sql.Date`, `Time`, `Timestamp` | moved without conversion |

The JDBC and `java.util` date types are in that list on purpose. They are what
a codebase of a certain age is made of, and refusing them would have made the
first step of every migration "rewrite every DTO before the mappers compile"
— which is the opposite of the point. Moving a property to `java.time` is
still the better end state; it is no longer a precondition.

What is left out is mostly what was never a column: `Map`, `Set`, `Optional`,
`Calendar`. `java.util.UUID` is out too — MyBatis ships no UUID handler either,
so there is no behaviour to match; store it as a `String`, or name a
`@Handler`.

The scanner reads the property types of every class your mappers name as a
`resultType`, `type`, `javaType` or `ofType`, so these appear in the report
rather than on your first build. Two limits worth knowing: it resolves a
simple name only through an actual `import`, and it can only read classes
inside the directory you scanned — a DTO from another module is passed over in
silence rather than guessed at.

A statement returning `Map` — `resultType="map"`, `resultType="hashmap"` — has
no result class at all. There is nothing to generate against, so it is a
blocker: declare a class with setters, or read the rows through the
[escape hatch](#escape-hatch).

**Type aliases become fully-qualified names.** A `typeAlias` is a lookup table
consulted while MyBatis reads your mappers, and there is no runtime
configuration to hold one:

```xml
<!-- mybatis-config.xml -->
<typeAliases><package name="com.example.model"/></typeAliases>

<!-- before -->            <!-- after -->
<select resultType="User"> <select resultType="com.example.model.User">
```

MyBatis's own aliases for JDK types (`string`, `long`, `int`, `date`, …) need
no change — LarkBatis takes those from the mapper method's return type and
never reads the attribute.

One `<package>` scan aliases every class under it, which is why a codebase
that uses one has an edit in nearly every statement while its mapper files
look perfectly ordinary. The scanner counts them for exactly that reason: it
reports the alias declarations it found and, separately, every `resultType`,
`parameterType`, `type`, `javaType` and `ofType` that is not a
fully-qualified name — the second number is the number of edits. It stops
there and does not say which class each alias meant. Resolving a simple name
across a package scan takes a compiler, and a migration report that names the
wrong class is worse than one that hands you the list and lets your IDE
resolve it.

<a id="mapper-interfaces"></a>
### Mapper interfaces

Two shapes that MyBatis accepts and the generator does not, both because a
mapper becomes one generated class named after the interface:

**Overloaded methods.** The SQL constants in the generated implementation are
named after the method, so `find(long)` next to `find(String)` would collide.
Rename one side.

**A mapper that `extends` another interface.** Statements are read from the
interface itself, so a method inherited from a shared base gets no
implementation. Generic CRUD base mappers — `interface UserMapper extends
BaseMapper<User>` — are the common case, and there is no equivalent: declare
every statement on the mapper interface itself. A base interface holding only
`default` methods is fine, since those are already implemented.

<a id="result-maps"></a>
### `<resultMap>`

Supported, narrowed to **one level of nesting over a join**, with the
ResultSet ordered by the parent key. That covers `<association>` and
`<collection>` in their common shapes.

Not supported: `extends`, nesting more than one level deep, `<discriminator>`,
and `<association select="...">` / `<collection select="...">` — the nested
select that issues N+1 queries at runtime. Express the mapping as a join, or
assemble the graph in Java from two statements.

There is also **no auto-mapping inside a result map**: it maps exactly what it
declares. A statement that wants name matching should use `resultType`.

A missing `<result>` column is a warning and the property stays unset, as in
MyBatis. A missing `<id>` column is an error, because that is what the
grouping loop reads.

<a id="foreach"></a>
### `<foreach>`

Supported over statically-typed collections; it compiles to two loops that
walk the same elements in the same order — one building SQL, one binding
parameters. `Map` iteration is not supported.

An empty collection throws rather than producing `IN ()`. Add `@PadPow2` to
round an IN-list up to a power of two if you want to bound how many distinct
SQL strings your statement cache sees.

<a id="generated-keys"></a>
### Generated keys and `<selectKey>`

`@Options(useGeneratedKeys = true, keyProperty = ..., keyColumn = ...)` is
supported. Both properties are required — MyBatis defaults `keyProperty` to
`"id"`, LarkBatis makes you say it. `keyColumn` is strongly recommended:
without explicit key columns, Oracle returns ROWID and PostgreSQL returns
every column.

`<selectKey>` is not supported. A `<selectKey>` that reads a sequence becomes
a statement of its own, called before the insert.

<a id="type-handlers"></a>
### Custom TypeHandlers

There is no handler discovery: nothing scans a package and nothing reads
`@MappedTypes`, because that is runtime type-based lookup, which is the thing
being removed. Handlers are written out instead, and every place MyBatis lets
you name one is read at build time: `typeHandler=` on `<id>`/`<result>`,
`typeHandler=` inside a `#{}`, and `@Handler(...)` on a result property or a
mapper parameter.

So a mapper XML that already names its handlers needs no edit. What does change
is the handler class itself: implement
`io.github.larkbatis.runtime.LarkBatisTypeHandler<J>` — `read(ResultSet, int)`
and `write(PreparedStatement, int, J)` — instead of
`org.apache.ibatis.type.TypeHandler<T>`. The two can be implemented side by
side on one class during a staged migration.

Three build-time rules the registry used to make unnecessary:

- The handler must be a public concrete class with a public no-argument
  constructor, and stateless: one instance is shared by every call site.
- Its type argument must be the value's own type, not a supertype.
- The handler owns `null` in both directions. There is no `jdbcType` to fall
  back on, so a handler that needs `setNull` calls it itself.

One reading per result class: two statements naming different handlers for the
same property is a build error, because one row reader is generated per class.
Two readings means two result classes.

A `<typeHandlers>` block in `mybatis-config.xml` carries across as one
compiler option, one `javaType:handler` pair per registration:

```
-Alarkbatis.typeHandlers=com.example.Money:com.example.MoneyHandler,\
                         com.example.Json:com.example.JsonHandler
```

Each pair applies to every property and every `#{}` of that type that does not
name a handler of its own, and every pair is checked during `javac` against the
rules above. An entry that moves nothing in the build is reported, because a
typo in the java-type half is otherwise completely silent.

Two things do not carry across. A handler MyBatis found through `<package>` or
`@MappedTypes` has to be written out as a pair — `larkbatis-scan` prints the
line for every `<typeHandler>` that names a `javaType`. And the `jdbcType` half
of MyBatis's `(javaType, jdbcType)` registry has no meaning here: the generated
reader knows the one column it is reading, so there is nothing to disambiguate.

Note one deliberate difference on the write side: binding a `null` through a
statically-typed slot calls `setNull(i, <the real JDBC type>)`, where MyBatis
sends `setNull(i, OTHER)` when no `jdbcType` is given. The type is known at
build time, and `OTHER` is what breaks some drivers.

<a id="escape-hatch"></a>
### Direct `SqlSession` calls, and the escape hatch

`sqlSession.selectList("com.example.UserMapper.findAll", args)` has no
compile-time type and no generated implementation behind it. Call the mapper
instead.

When you genuinely need SQL that cannot be known at build time — a reporting
query assembled from user-selected columns, a stored procedure — use the
escape hatch. It still goes through `SqlFragment`, so it remains greppable,
and rows are still read by a generated reader, so the result type is still
checked:

```java
default List<User> recent(LarkBatisSession s, int limit) {
    return s.query(
            SqlFragment.unsafeRawSql("SELECT id, name FROM users LIMIT " + limit),
            ps -> { },
            UserRow.READER);
}
```

`statementType="CALLABLE"` and `"STATEMENT"` go through the same door.

<a id="spring"></a>
### Spring

The starter auto-configures a `LarkBatisSession` over your `DataSource`, and
the processor emits a `@Configuration` registering every mapper as a bean —
this replaces `@MapperScan`. Transactions work through `DataSourceUtils`, so
`@Transactional` behaves exactly as it did.

Two things to check:

- The generated `@Configuration` lands in the mappers' common package. If your
  mappers live outside the `@SpringBootApplication` scan root, either import
  it explicitly or set `-Alarkbatis.springConfigPackage`.
- **More than one `DataSource` is not supported yet.** The auto-configuration
  uses `@ConditionalOnSingleCandidate` and contributes nothing rather than
  guessing, so a multi-DataSource application will get a
  `NoSuchBeanDefinitionException` instead of a wrong wiring.

`log-sql` has no equivalent: every generated body would have to carry a
logging branch. SQL logging belongs to the driver or the pool.

<a id="dropped-features"></a>
### Dropped outright

These have no LarkBatis equivalent and no plan to gain one. Each exists only
because MyBatis interprets at runtime.

| Feature | What to do instead |
|---|---|
| Plugins / `Interceptor` | One replacement per plugin kind, below |
| `@SelectProvider` and family | Move the SQL into the mapper, or use the escape hatch |
| Lazy loading | Fetch eagerly with a join, or split into two statements |
| Second-level cache (`<cache>`, `@CacheNamespace`) | Cache in the service, where invalidation is visible |
| `RowBounds` | Page in SQL with `LIMIT`/`OFFSET` as real parameters |
| `<bind>` | Compute the value in Java and pass it in |
| `<discriminator>` | Separate statements with separate result types |
| `<parameterMap>` | `#{}` with typed parameters (deprecated in MyBatis too) |
| `objectFactory`, `objectWrapperFactory`, `reflectorFactory` | Nothing — these hook the reflection layer that no longer exists |
| Runtime `addMapper()` | The generated registry is a closed list, by design |

**Plugins are the blocker most codebases hit first**, so they are worth more
than a table row. MyBatis applies an `Interceptor` to exactly four objects —
`Executor`, `StatementHandler`, `ParameterHandler`, `ResultSetHandler` — each
built through a `Configuration` factory method ending in
`interceptorChain.pluginAll(...)` and wrapped by `Plugin.wrap` with a
`Proxy.newProxyInstance`. Those four objects are precisely what a generated
method body replaces: it borrows a `Connection`, prepares a constant SQL
string, binds with typed setters chosen at build time and reads rows through a
generated `RowReader`. There is nothing in between to wrap, and the wrapping
call is the one call generated code never makes.

| Plugin | What replaces it |
|---|---|
| Paging (PageHelper and friends) | `LIMIT`/`OFFSET` as ordinary `#{}` parameters, plus a count statement of its own. The page size stops being ambient thread state |
| Auditing — `created_at`, `updated_by` | Set the fields in the service, give the column a database default, or put them in a `<sql>` fragment the inserts include |
| Soft delete | `AND deleted = false` in the statement, or a `<sql>` fragment every select includes — it cannot be forgotten by a query that bypassed the interceptor |
| Column encryption or masking | A `LarkBatisTypeHandler`, registered once for the type with `-Alarkbatis.typeHandlers`. This one maps across almost exactly |
| SQL logging | The driver or the pool: `net.ttddyy:datasource-proxy`, p6spy |
| Multi-tenancy, dynamic table or schema names | A `SqlFragment` through `${}`, the one audited gate for SQL text |
| Timing, metrics, tracing | A decorator around the mapper bean, or Spring AOP on it |

A mapper bean is an ordinary object here. In MyBatis the mapper *is* a JDK
proxy, which is a large part of why wrapping behaviour around it means writing
an interceptor; a generated `UserMapper$$Impl` registered as a normal bean
takes Spring AOP, or a hand-written decorator implementing the same interface.

---

## What you get back

- **No reflection, no proxies, no OGNL** on the query path, and no
  `META-INF/native-image` metadata to hand-write.
- **Mapper bugs become compile errors.** A wrong parameter type, a missing
  setter, a typo'd property — javac catches them, because the mapper now has a
  real implementation.
- **You can read and debug the SQL.** Open `UserMapper$$Impl.java`, set a
  breakpoint inside an `<if>` branch, and get a stack trace that points at a
  real line of Java.
- **Measured speedups on the row-read path**, proportional to rows returned.
  See `BENCHMARKS.md` — including the honest half: for a single-row `findById`
  against a real database, the difference is noise.

## What it costs

- **Changing SQL means rebuilding.** For a team used to editing mapper XML and
  restarting, this is a real change in workflow.
- **Build time moves left.** Generation happens in javac, on every developer's
  machine, instead of at startup in production.
- **Every `${}` call site has to be touched.** Proportional to call sites, not
  to mappers — and it is the first time anyone will have looked at each raw
  SQL insertion point in the codebase.
