# lightbatis

LightBatis core — an ahead-of-time MyBatis: SQL and mappers are compiled into
plain Java code at build time. No proxies, no reflection, no OGNL at runtime.

| Module | Scope | Role |
|---|---|---|
| `lightbatis-annotations` | runtime | `@Select @Insert @Update @Delete @Param @Options @Column @OrderBy @Handler @LightBatisRow` — no logic |
| `lightbatis-runtime` | runtime | `LightBatisSession`, `LightBatisTx`, `JdbcCodec`, `SqlFragment`, `LightBatisSql` — zero dependencies beyond JDBC |
| `lightbatis-processor` | build-only | Frontend → IR (`MapperModel`) → emitters; runs as a plain annotation processor. Never belongs on a runtime classpath |
| `lightbatis-sample` | not published | End-to-end sample app + tests; the native-image smoke-test subject |

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

## Using it (M1: annotation mappers, static SQL)

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
(`LightBatisMappers`). Wire it up:

```java
JdbcLightBatisSession session = new JdbcLightBatisSession(dataSource);
UserMapper mapper = LightBatisMappers.userMapper(session);

try (LightBatisTx tx = session.begin()) {
    mapper.insert(user);
    tx.commit();
}
```

### Gradle

```kotlin
dependencies {
    implementation("io.github.lightbatis:lightbatis-annotations:0.1.0-SNAPSHOT")
    implementation("io.github.lightbatis:lightbatis-runtime:0.1.0-SNAPSHOT")
    annotationProcessor("io.github.lightbatis:lightbatis-processor:0.1.0-SNAPSHOT")
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>io.github.lightbatis</groupId>
    <artifactId>lightbatis-annotations</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>io.github.lightbatis</groupId>
    <artifactId>lightbatis-runtime</artifactId>
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
            <groupId>io.github.lightbatis</groupId>
            <artifactId>lightbatis-processor</artifactId>
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
| `lightbatis.registryPackage` | Package for the generated `LightBatisMappers` (default: common package prefix of all mappers) |
| `lightbatis.mapperDir` | Reserved for M2: directory of mapper XML files |

Pass them as compiler args — Gradle:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Alightbatis.registryPackage=com.example.app")
}
```

Maven: add `<compilerArgs><arg>-Alightbatis.registryPackage=com.example.app</arg></compilerArgs>`
to the maven-compiler-plugin configuration.

> **M2 note (mapper XML):** the processor will read mapper XML with plain
> `java.io`, outside the compiler's `Filer`. Until the build-tool plugins land
> (M3), Gradle cannot know the XML files are compile inputs — after editing
> only an XML file, run `clean` before `build`.

## `${}` and raw SQL

A `String` parameter bound to `${}` is a **compile error**. The accepted forms
(design §08): `SqlFragment` (created via `allowed(...)`, `identifier(...)`, or
the grep-able `unsafeRawSql(...)`), closed-value types (`int`, `long`,
`short`, `byte`, `boolean`, enums), or a `String` parameter annotated
`@OrderBy(allowed = {...})` (compiled to a switch). Every statement containing
`${}` also gets a `LightBatisSql.trackVariants` call so unbounded SQL-variant
growth shows up in a log line instead of a statement-cache incident.

## Developing

- The hand-written emitter spec (what generated code must look like) lives in
  `lightbatis-runtime/src/test/java/io/github/lightbatis/runtime/handwritten/`.
- Golden snapshots of generated code:
  `lightbatis-processor/src/test/resources/golden/`. After an intended emitter
  change, refresh with `./gradlew test -Pupdate-golden` and review the diff.
- Every "this is a compile error" promise has a test in
  `lightbatis-processor/src/test/java/.../CompileFailTest.java`.

Design document: see `../docs/lightbatis-design.md` in the parent workspace
(section references like §04 in code comments point there); build plan:
`../docs/lightbatis-build-plan.md`.
