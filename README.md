# larkbatis

LarkBatis core — an ahead-of-time MyBatis: SQL and mappers are compiled into
plain Java code at build time. No proxies, no reflection, no OGNL at runtime.

| Module | Scope | Role |
|---|---|---|
| [`larkbatis-annotations`](larkbatis-annotations/README.md) | runtime | `@Select @Insert @Update @Delete @Mapper @Param @Options @Column @OrderBy @PadPow2 @Handler @LarkBatisRow` — no logic |
| [`larkbatis-runtime`](larkbatis-runtime/README.md) | runtime | `LarkBatisSession`, `LarkBatisTx`, `JdbcCodec`, `SqlFragment`, `LarkBatisSql` — zero dependencies beyond JDBC |
| [`larkbatis-processor`](larkbatis-processor/README.md) | build-only | Frontend → IR (`MapperModel`) → emitters; runs as a plain annotation processor. Never belongs on a runtime classpath |
| [`larkbatis-scanner`](larkbatis-scanner/README.md) | build-only | `larkbatis-scan` — reads an existing MyBatis codebase and reports what migrating it would cost |
| [`larkbatis-sample`](larkbatis-sample/README.md) | not published | End-to-end sample app + tests; the native-image smoke-test subject |
| [`larkbatis-conformance`](larkbatis-conformance/README.md) | not published | Differential harness: generated SQL vs the MyBatis oracle |
| [`larkbatis-benchmarks`](larkbatis-benchmarks/README.md) | not published | JMH suite behind the numbers in `BENCHMARKS.md` |

Build: `./gradlew build` (JDK 17 via toolchain).

**Compiler support: javac only.** The processor relies on javac behavior
(declaration order of elements, multi-round generated-type resolution). ECJ /
Eclipse compilation is not supported.

**Compile with `-parameters` under Gradle.** Clean builds read parameter names
from the AST, but Gradle *incremental* builds re-run aggregating processors
over unchanged mappers from their class files, where names only survive with
`-parameters` (a documented Gradle limitation). Add
`options.compilerArgs.add("-parameters")` — or name every parameter with
`@Param`.

## Using it — annotation mappers, static SQL

Write a mapper interface:

```java
public interface UserMapper {

    @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id}")
    User findById(long id);

    @Insert("INSERT INTO users (name, email, created_at) VALUES (#{name}, #{email}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User u);
}
```

The processor generates `UserMapper$$Impl` (plain JDBC, breakpoint-able), one
row reader per result class (`UserRow`), and a static registry
(`LarkBatisMappers`). Wire it up:

```java
JdbcLarkBatisSession session = new JdbcLarkBatisSession(dataSource);
UserMapper mapper = LarkBatisMappers.userMapper(session);

try (LarkBatisTx tx = session.begin()) {
    mapper.insert(user);
    tx.commit();
}
```

### Gradle

```kotlin
dependencies {
    implementation("io.github.larkbatis:larkbatis-annotations:0.1.0-SNAPSHOT")
    implementation("io.github.larkbatis:larkbatis-runtime:0.1.0-SNAPSHOT")
    annotationProcessor("io.github.larkbatis:larkbatis-processor:0.1.0-SNAPSHOT")
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>io.github.larkbatis</groupId>
    <artifactId>larkbatis-annotations</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>io.github.larkbatis</groupId>
    <artifactId>larkbatis-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>io.github.larkbatis</groupId>
            <artifactId>larkbatis-processor</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Processor options

| Option | Meaning |
|---|---|
| `larkbatis.registryPackage` | Package for the generated `LarkBatisMappers` (default: common package prefix of all mappers) |
| `larkbatis.mapperDir` | Directories of mapper XML, comma- or path-separator-separated. Only files whose root element is `<mapper>` are read |
| `larkbatis.springConfig` | `false` suppresses the generated Spring `@Configuration`. Default: emitted when spring-context is on the build classpath |
| `larkbatis.springConfigPackage` | Package for the generated `LarkBatisMapperConfiguration` (default: same as `registryPackage`) |

Pass them as compiler args — Gradle:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Alarkbatis.registryPackage=com.example.app")
}
```

Maven: add `<compilerArgs><arg>-Alarkbatis.registryPackage=com.example.app</arg></compilerArgs>`
to the maven-compiler-plugin configuration.

> **Lombok:** declare `larkbatis-processor` **after** `org.projectlombok:lombok`
> in the `annotationProcessor` configuration. Lombok writes its getters and
> setters into the AST when its own processor runs, and javac runs discovered
> processors in classpath order — declared first, LarkBatis sees a result class
> with no accessors at all. The error message says so when it spots a Lombok
> annotation on the class, but the fix is one line of ordering:
>
> ```kotlin
> annotationProcessor("org.projectlombok:lombok")
> annotationProcessor("io.github.larkbatis:larkbatis-processor:0.1.0-SNAPSHOT")  // after
> ```

> **Mapper XML and incremental builds:** the processor reads mapper XML with
> plain `java.io`, outside the compiler's `Filer`, so the build tool has to be
> told those files are compile inputs. Use `larkbatis-gradle-plugin` or
> `larkbatis-maven-plugin`, which do exactly that (and pass the option for
> you). Setting `-Alarkbatis.mapperDir` by hand works, but then editing only
> an XML file may not regenerate — run `clean` first.

## Java modules (JPMS)

`larkbatis-annotations` and `larkbatis-runtime` ship real named modules, not
automatic ones. A modular application needs three directives — the third is
the one that surprises people:

```java
module com.example.app {
    requires io.github.larkbatis.runtime;             // what generated bodies call
    requires static io.github.larkbatis.annotations;  // CLASS retention: compile time only
    requires static java.compiler;                     // @Generated on every emitted source
}
```

Without `requires static java.compiler`, javac rejects the *generated* file
with "package javax.annotation.processing is not visible". You do **not** need
`requires java.sql`: the runtime requires it transitively, because its own API
hands you `Connection`, `ResultSet` and `PreparedStatement`. Your JDBC driver
may need directives of its own — H2 needs `requires java.naming`.

Generated code lands in your package, so nothing has to be exported for it,
and neither LarkBatis module ever needs `opens`: there is no reflection to
open anything for. `larkbatis-sample` is a working modular consumer.

## Mapper XML and dynamic SQL

An interface annotated `@Mapper` takes each method's SQL from the XML
statement with the same id (namespace = the interface's fully-qualified name).
`<if> <choose> <where> <set> <trim> <sql>/<include>` are folded at build time
into condition locals and guarded appends — the runtime evaluates the `test`
conditions and nothing else.

`<foreach>` compiles to two loops that walk the same elements in the same
order: one appends the placeholders, one binds the values. There is no
`__frch_*` naming layer to route values through, because the loop index
already connects them.

```xml
<select id="findByIds" resultType="com.example.User">
  SELECT id, name FROM users WHERE id IN
  <foreach collection="list" item="id" open="(" separator="," close=")">#{id}</foreach>
</select>
```

Collections, arrays and `Map<K,V>` (key to `index`, value to `item`) iterate;
loops nest. An **empty collection throws** `LarkBatisEmptyForeachException`
naming the parameter, rather than letting `... WHERE id IN` reach the database
— wrap the loop in `<if test="ids != null and !ids.isEmpty()">` when the
fragment should simply disappear. Annotate a method (or the interface) with
`@PadPow2` to round an `IN` list's placeholder count up to the next power of
two, which bounds how many distinct SQL texts the statement can produce.

The `test` attribute takes a narrow grammar, not OGNL: null checks,
comparisons on typed property paths, `and/or/not`, `size()/length()/isEmpty()`
and boolean methods. MyBatis truthiness is deliberately absent —
`test="count"` is a compile error asking for `count != 0`.

## `Stream` returns

A mapper method may return `Stream<T>` instead of `List<T>`. The rows then
arrive one at a time off an open cursor, which is the point — a result set too
big to hold never becomes a list.

```java
@Select("SELECT id, name, email, created_at FROM users ORDER BY id")
Stream<User> streamAll();
```

```java
try (Stream<User> rows = mapper.streamAll()) {
    rows.filter(User::isActive).forEach(exporter::write);
}
```

**The caller closes it.** This is the one generated shape whose JDBC resources
outlive the method that opened them, so the generated body has no `finally`:
closing the stream is what closes the ResultSet and the statement and releases
the Connection. Outside a transaction, a stream that is never closed holds a
pooled Connection for as long as it is reachable; inside one, the transaction
still owns the Connection and the stream holds the cursor until it ends. The
failure path — anything that throws before the stream exists — undoes all of it
by hand.

The stream is sequential on purpose: splitting a cursor means reading ahead
into memory, which is what a `Stream` return was chosen to avoid.

Scalar element types work (`Stream<String>` reads column 1), and so does the
escape hatch (`LarkBatisSession.queryStream`). A `Stream` return over a nested
`<resultMap>` is a compile error: a parent spans several rows, so it is only
complete once the next parent starts, and answering that from a one-row-at-a-
time cursor means buffering the whole result.

## `<resultMap>` and one-level joins

A `<resultMap>` declares the column each property comes from, and may fill one
nested `<association>` (a single child object) or `<collection>` (a `List`)
from the same join:

```xml
<resultMap id="teamWithMembers" type="com.example.Team">
  <id     property="id"   column="t_id"/>
  <result property="name" column="t_name"/>
  <collection property="members" ofType="com.example.Member">
    <id     property="id"     column="m_id"/>
    <result property="name"   column="m_name"/>
    <result property="jersey" column="m_jersey"/>
  </collection>
</resultMap>

<select id="findAll" resultMap="teamWithMembers">
  SELECT t.id AS t_id, t.name AS t_name,
         m.id AS m_id, m.name AS m_name, m.jersey AS m_jersey
  FROM team t LEFT JOIN member m ON m.team_id = t.id
  ORDER BY t.id, m.jersey
</select>
```

The generated body is a loop that starts a new parent where the `<id>` column
changes, and skips the child when its `<id>` column is NULL — a LEFT JOIN
miss. MyBatis does the same job by building a `CacheKey` per row (reflect over
the id columns, read each through a TypeHandler, hash, look the parent up in a
map); here the key is a typed local compared with `!=`, so a `long` key costs
no boxing per row.

**The ResultSet must be ordered by the parent key.** That is the price of not
keeping the map: rows that revisit a key after another parent's rows produce a
second parent object instead of merging. Statements with no `ORDER BY` at all
get a build-time note.

Narrowed on purpose — each is a compile error naming the
replacement:

| Not supported | Instead |
|---|---|
| More than one level of nesting, or two nested mappings in one map | One join, one grouping key |
| `select=` on `<association>`/`<collection>` (nested select) | Write the join — the nested select is the N+1 it avoids |
| `resultMap=` inside a nested mapping | Spell the child's `<id>`/`<result>` out, which keeps the one-level limit visible |
| `columnPrefix` | Alias the child columns in the select list |
| `extends`, `<constructor>`, `<discriminator>`, `autoMapping` | Spell the mappings out; result classes are built with setters |
| `<id column="x"/>` with no property | Map the key to a property and mark that `<id>` |
| A type alias in `type`/`ofType`/`javaType` | The fully-qualified class name |

There is **no auto-mapping**: a result map maps exactly what it declares. A
statement that wants columns matched to property names by name uses
`resultType` instead. A `<result>` column missing from the select list is a
build warning and leaves the property unset; a missing `<id>` column is an
error, because that is what the loop reads.

When the select list parses, positions are constants. When it does not
(`SELECT *`), the statement gets its own generated resolver that reads
`ResultSetMetaData` once on the first row and matches the column names the map
declared.

## `${}` and raw SQL

A `String` parameter bound to `${}` is a **compile error**. The accepted forms
are: `SqlFragment` (created via `allowed(...)`, `identifier(...)`, or
the grep-able `unsafeRawSql(...)`), closed-value types (`int`, `long`,
`short`, `byte`, `boolean`, enums), or a `String` parameter annotated
`@OrderBy(allowed = {...})` (compiled to a switch). Every statement whose SQL
text is not fixed at build time — a `${}` splice or a `<foreach>`, whose
cardinality changes the text just as much — gets a
`LarkBatisSql.trackVariants` call, so unbounded SQL-variant growth shows up
in a log line instead of a statement-cache incident.

## Coming from MyBatis

Start with the scanner. It compiles nothing and resolves no dependencies, so
it runs on a checkout that has never been built:

```bash
./gradlew :larkbatis-scanner:installDist
larkbatis-scanner/build/install/larkbatis-scan/bin/larkbatis-scan /path/to/service
```

Or, in a project that already applies the Gradle plugin, `./gradlew
larkbatisScan`.

It reports, statement by statement and with line numbers, how much of the
codebase compiles as-is, what needs a mechanical edit, what needs a decision,
and what is blocked on a feature LarkBatis dropped. `MIGRATION.md` explains
every finding it can print, and is the document to read next.

## Numbers

`BENCHMARKS.md` has the measured comparison against MyBatis — the row-read
path, dynamic SQL, startup, megamorphic behaviour, and a single-row lookup
over a real socket where the difference is honestly nothing. Re-run it with:

```bash
./gradlew :larkbatis-benchmarks:jmh                  # ~25 min, machine should be idle
./gradlew :larkbatis-benchmarks:jmh -Pquick          # smoke run
./gradlew :larkbatis-benchmarks:jmh -Pbench=RowRead  # one class
./gradlew :larkbatis-benchmarks:jmh -PbenchJdk=21    # another JDK
```

## Developing

- The hand-written emitter spec (what generated code must look like) lives in
  `larkbatis-runtime/src/test/java/io/github/larkbatis/runtime/handwritten/`.
- Golden snapshots of generated code:
  `larkbatis-processor/src/test/resources/golden/`. After an intended emitter
  change, refresh with `./gradlew test -Pupdate-golden` and review the diff.
- Every "this is a compile error" promise has a test in
  `larkbatis-processor/src/test/java/.../CompileFailTest.java`.

Design document: see `../docs/larkbatis-design.md` in the parent workspace;
build plan: `../docs/larkbatis-build-plan.md`.
