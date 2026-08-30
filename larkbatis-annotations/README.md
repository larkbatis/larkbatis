# larkbatis-annotations

Mapper annotations, and nothing else. Twelve annotation types, no logic, no
dependencies. Everything that *reads* these annotations lives in
`larkbatis-processor` and runs at build time.

Published: `io.github.larkbatis:larkbatis-annotations`.

## What it does

Gives a mapper interface a vocabulary the annotation processor can compile:

| Annotation | Target | Meaning |
|---|---|---|
| `@Mapper` | interface | Statements come from mapper XML with this interface's FQN as the namespace. Also what makes an interface with no annotated methods visible to the processor |
| `@Select` `@Insert` `@Update` `@Delete` | method | The SQL for this method. `String[]` so a long statement can be written as several lines |
| `@Param("name")` | parameter | The name `#{}` and `<if test>` use for this parameter |
| `@Options` | method | `useGeneratedKeys`, `keyProperty`, `keyColumn` |
| `@OrderBy(allowed = {...})` | parameter | Lets a `String` parameter reach `${}` — compiled into a switch over the whitelist |
| `@PadPow2` | method or interface | Rounds a `<foreach>` `IN` list's placeholder count up to the next power of two, bounding the number of distinct SQL texts |
| `@Column("col")` | field, setter or getter | The column this result property reads from, where the `snake_case` → `camelCase` convention is not enough. Read on all three sites; two of them disagreeing is a compile error |
| `@LarkBatisRow` | class | Generate a row reader for this class even though no statement returns it — the escape hatch needs one to pass to `s.query(...)` |

| `@Handler(MoneyHandler.class)` | parameter, field, setter or getter | The `LarkBatisTypeHandler` that moves this value. Named, never discovered; also lifts the type whitelist for the value it moves |

Every annotation here is now read by the processor.

`@Handler` takes an unbounded `Class<?>` on purpose. Bounding it to
`Class<? extends LarkBatisTypeHandler<?>>` would make this artifact depend on
`larkbatis-runtime`, and it has no dependencies — that is the whole point of
splitting it out, and it is what lets a consumer declare it `requires static`.
The check javac would have made is made by the processor instead, which also
gets to say *why* a handler was rejected.

## How it works

Every annotation is `RetentionPolicy.CLASS`. They exist for javac and for the
processor, and are gone by the time the application runs — which is the whole
point: a mapper carries no runtime metadata because nothing at runtime reads
metadata.

That retention is also why the JPMS descriptor is what it is. Consumers depend
on this module at compile time only:

```java
module com.example.app {
    requires static io.github.larkbatis.annotations;  // compile-time-only edge
}
```

`module-info.java` exports the one package and requires nothing at all — the
zero-dependency red line is trivially true here.

## How to run

Nothing to run; there is no behaviour to test. It builds as part of the repo:

```bash
./gradlew :larkbatis-annotations:build
```

## Where it fits

```
your mapper interface
    @Select / @Param / @Options        (this module)
        ↓ read by javac + LarkBatisProcessor at build time
    UserMapper$$Impl.java              (larkbatis-processor emits it)
        ↓ calls
    LarkBatisSession, JdbcCodec …     (larkbatis-runtime)
```

An application needs this module on `implementation` (or `compileOnly`) and
`larkbatis-processor` on `annotationProcessor`. See the repo
[README](../README.md) for the full dependency block.
