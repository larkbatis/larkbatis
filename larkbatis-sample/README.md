# larkbatis-sample

A small application that exercises the whole chain outside the processor's own
tests: a real `javac` run through the annotation processor, real generated
code, a real H2 database.

Never published. Also the **native-image smoke-test subject** — there is no
reflection anywhere, so `native-image` needs no `reflect-config` for the mapper
layer. (GraalVM is not installed on this machine; that check is still open.)

## What it demonstrates

| Source | Path it covers |
|---|---|
| `UserMapper` | Annotation mappers, static SQL, `@Options(useGeneratedKeys)`, batch insert, `@OrderBy` compiled to a switch, scalar reads, `Stream` returns, and the manual escape hatch |
| `UserSearchMapper` + `UserSearchMapper.xml` | Mapper XML, dynamic tags, the folded `<where>`/`<set>` |
| `UserBatchMapper` + `UserBatchMapper.xml` | `<foreach>` — placeholder loop and bind loop |
| `TeamMapper` + `TeamMapper.xml` | One-level `<resultMap>` joins: `<collection>`, `<association>`, a flat map, and the name-based variant over an unparseable select list |
| `module-info.java` | The consumer side of the JPMS story |

`SampleApp` is the M1 done-criterion in executable form: build a `JdbcDataSource`
directly (no `DriverManager`, no `Class.forName`), get a mapper from the
generated `LarkBatisMappers`, insert inside a `LarkBatisTx`, read a `User`
back with zero reflection.

## The modular consumer

`module-info.java` is the reference for what a modular application has to
declare — three directives, and the third surprises people:

```java
module com.example.lbsample {
    requires io.github.larkbatis.runtime;             // what generated bodies call
    requires static io.github.larkbatis.annotations;  // CLASS retention: compile-time only
    requires static java.compiler;                     // @Generated on every emitted source
    requires com.h2database;                           // automatic module, from the jar manifest
    requires java.naming;                              // H2's JdbcDataSource is Referenceable
}
```

Without `requires static java.compiler`, javac rejects the **generated** file
with "package javax.annotation.processing is not visible". `java.sql` is not
listed because `larkbatis-runtime` requires it transitively. The last two have
nothing to do with LarkBatis — they are H2's.

Generated mappers land in this module's own package, so nothing has to be
exported for them.

## How mapper XML reaches the processor

The processor reads XML with plain `java.io`, so the build has to tell it
where to look and tell Gradle that those files are compile inputs:

```kotlin
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Alarkbatis.mapperDir=" + ...("src/main/resources/mappers"))
    inputs.dir(layout.projectDirectory.dir("src/main/resources/mappers"))
}
```

`larkbatis-gradle-plugin` does exactly this for a real project; it is spelled
out here because this repo cannot depend on its own plugin.

## How to run

```bash
./gradlew :larkbatis-sample:run     # SampleApp against in-memory H2
./gradlew :larkbatis-sample:test    # the end-to-end suites
./gradlew :larkbatis-sample:build   # compile + test + jar
```

To read the generated code — which is the point of it being readable — look in
`build/generated/sources/annotationProcessor/java/main/com/example/lbsample/`:
`UserMapper$$Impl.java`, `UserRow.java`, `LarkBatisMappers.java`.

## The tests

Each one pins something that only a real database can decide:

| Test | What it proves |
|---|---|
| `GeneratedMapperEndToEndTest` | Generated code (not the hand-written spec) against real JDBC: generated keys, batch keys, the `@OrderBy` switch, scalar reads, transactions, and the escape hatch reusing the generated reader |
| `DynamicMapperEndToEndTest` | Every branch combination of the folded `<where>`/`<set>` produces SQL H2 accepts — a stripped `AND` or a trailing comma fails loudly here |
| `ForeachMapperEndToEndTest` | The placeholder loop and the bind loop agree: H2 rejects any mismatch between the number of `?` and the number of bound values. Also pins the empty-collection throw, which has no oracle in the differential harness |
| `ResultMapEndToEndTest` | The cases that only exist because the join is real: a parent with no children (LEFT JOIN row, all child columns NULL), two parents in one ResultSet, children arriving in a different order than the key |
| `StreamMapperEndToEndTest` | The lifecycle, via `CountingDataSource` — this is the only generated shape whose Connection outlives the method that opened it, so every assertion is really about the connection count |
