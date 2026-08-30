# larkbatis-processor

The generator. A plain `javax.annotation.processing.Processor` that turns
mapper interfaces (and their XML) into plain Java source at build time:
mapper implementations, row readers, a static registry, and — in a Spring
build — a `@Configuration`.

**Build-only.** It must never appear on an application's runtime classpath.
Published so build tools can put it on `annotationProcessor` /
`annotationProcessorPaths`, and nowhere else.

~7,500 lines. One dependency: `com.palantir.javapoet` (the maintained fork;
square/javapoet has been archived since 10/2024), kept behind `emit.SourceWriter`
so a hand-rolled emitter could replace it later.

## What it generates

For a mapper `com.example.UserMapper`:

| Generated type | Where | What it is |
|---|---|---|
| `UserMapper$$Impl` | mapper's package | One class per mapper. A constructor taking `LarkBatisSession`, one method per statement, SQL as `private static final String` constants with the `?` already in place |
| `UserRow` | result class's package | One row reader per **result class**, shared by every statement returning it |
| `LarkBatisMappers` | common package prefix of all mappers | The static, closed registry: one factory method per mapper. It replaces classpath scanning — nothing is registered at runtime |
| `LarkBatisMapperConfiguration` | same package | Emitted only when spring-context is on the build classpath: one `@Bean` per mapper, `proxyBeanMethods = false` |

Generated code is a feature, not an artifact: readable, breakpoint-able, and
free of anything you would not have written by hand.

## Pipeline

```
@Mapper interface (+ mapper XML)
        │
   ┌────┴─────────────────────────── frontend ────────────────────────────┐
   │ MapperXmlParser   → DynNode tree (<if>/<choose>/<trim>/<foreach>…)   │
   │ AnnotationFrontend→ walks the interface, validates every shape       │
   │ SqlTokenizer      → text / #{} / ${} tokens                          │
   │ ExprCompiler      → <if test> grammar → a Java boolean expression    │
   │ DynamicLowering   → condition locals + guarded SQL segments          │
   │ TypeResolver      → Java type → ValueKind (the closed JDBC whitelist)│
   │ SelectListParser  → can we control the column order?                 │
   └────┬──────────────────────────────────────────────────────────────────┘
        │  MapperModel (IR — no javax.lang.model types in it)
   ┌────┴───────────────────────────── emit ──────────────────────────────┐
   │ MapperImplEmitter  → UserMapper$$Impl                                │
   │ RowReaderEmitter   → UserRow                                         │
   │ RegistryEmitter    → LarkBatisMappers                               │
   │ SpringConfigurationEmitter → LarkBatisMapperConfiguration           │
   └────┬──────────────────────────────────────────────────────────────────┘
        │  SourceWriter (FilerSourceWriter in a real build)
     generated .java → compiled in the same javac run
```

Two frontends, one IR. `MapperModel` deliberately contains no
`javax.lang.model` types, so it can be built outside javac — which is what lets
`larkbatis-scanner` reuse the same frontend as its oracle.

### What the frontend decides

- **`#{}` → a `?` plus a typed bind.** The SQL text is never parsed again at
  runtime. `SqlTokenizer` is a faithful port of MyBatis's `GenericTokenParser`,
  applied in MyBatis's own order (`${}` first, then `#{}` on the remaining
  literal text), escapes and unclosed-token behaviour included.
- **`<if test>` → one `boolean cN = ...` local**, computed once and reused for
  both SQL assembly and parameter binding. The grammar is narrow: null checks,
  comparisons over statically-typed property paths, `and`/`or`/`not`,
  `size()`/`length()`/`isEmpty()`, boolean-returning methods, bare booleans.
  OGNL truthiness is *not* reproduced — `test="count"` is a compile error
  asking for `count != 0`. Null semantics are fixed and documented rather than
  coerced.
- **`<where>`/`<set>`/`<trim>` → constant-folded**, with the prefix/suffix
  stripping expressed as `SqlPiece.Alt` pieces whose condition is "did anything
  before/after me contribute".
- **Row reader mode.** `ReaderAccess` crosses two axes — are positions known at
  build time, and was the column declared or inferred:
  `POSITIONAL_CANONICAL` (select list matches property order → `read(rs)`),
  `POSITIONAL_CUSTOM` (reordered/partial → a constant `int[]`), `NAME_BASED`
  and `NAME_BASED_MAPPED` (select list unparseable → indexes resolved once from
  `ResultSetMetaData`). Every downgrade carries a reason, printed at build time.
- **Types.** `ValueKind` is a closed whitelist. A type outside it is a clear
  compile error naming the element — generating wrong code is far worse than
  refusing to generate.

Every rejection is a compile error reported on the precise element, so it lands
on the right line in an IDE. Emitters may assume a valid model.

## Processor options

| Option | Meaning |
|---|---|
| `-Alarkbatis.mapperDir` | Directories of mapper XML, comma- or path-separator-separated. Only files whose root element is `<mapper>` are read |
| `-Alarkbatis.registryPackage` | Package for `LarkBatisMappers` (default: the common package prefix of all mappers) |
| `-Alarkbatis.springConfig` | `false` suppresses the Spring `@Configuration` |
| `-Alarkbatis.springConfigPackage` | Package for `LarkBatisMapperConfiguration` |

Mapper XML is read with plain `java.io`, not through the `Filer` — the
`Filer.getResource` spec does not guarantee access to `src/main/resources`,
which is the entire reason `larkbatis-gradle-plugin` and
`larkbatis-maven-plugin` exist. They pass this option and register the XML as
compile inputs so editing a mapper actually regenerates.

An XML namespace that matches no compiled `@Mapper` interface produces a
build warning naming the file, in the last round — a silently ignored mapper
file is the worst possible failure mode.

## Constraints worth knowing

- **javac only.** The processor relies on javac behaviour (declaration order of
  elements, multi-round resolution of generated types). ECJ is not supported.
- **Declared `aggregating`** for Gradle incremental processing: a result class
  can be the result type of several mappers and the registry spans all of them,
  so outputs have no single originating element.
- **Under Gradle, compile with `-parameters`** (or name every parameter with
  `@Param`). Incremental builds re-run aggregating processors over unchanged
  mappers from their class files, where parameter names only survive with that
  flag.
- **Order after Lombok** in the `annotationProcessor` configuration. javac runs
  discovered processors in classpath order; declared first, LarkBatis sees a
  result class with no accessors yet.

## How to run

It runs inside javac, so "running it" means compiling something:

```bash
./gradlew :larkbatis-processor:test        # compile-testing + golden files
./gradlew :larkbatis-sample:build          # a real javac run, end to end
```

Tests use `com.google.testing.compile` to compile fixture mappers in memory and
assert on the emitted sources, which is why the build passes a long list of
`--add-exports jdk.compiler/...` JVM args.

**Golden files** live in `src/test/resources/golden/`. After an intended
emitter change:

```bash
./gradlew :larkbatis-processor:test -Pupdate-golden   # then review the diff
```

Every "this is a compile error" promise has a case in `CompileFailTest`,
`XmlMapperFailTest` or `ResultMapFailTest`, asserting on the message and not
just on the failure. The
generated output is also checked against the MyBatis oracle by the
[`larkbatis-conformance`](../larkbatis-conformance/README.md) differential
harness — that suite is what any emitter change ultimately answers to.
