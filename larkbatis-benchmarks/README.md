# larkbatis-benchmarks

The JMH suite behind [`BENCHMARKS.md`](../BENCHMARKS.md). It replaces the
design document's "expected" column with measured numbers, and closes the four
things the design itself listed as unmeasured: startup time, single-query
latency with a real round trip, megamorphic behaviour, and the benchmark
hygiene (bean count, column count, how allocation was measured, which JDK).

Never published. MyBatis 3.5.19 is the comparison subject and must never appear
on a LarkBatis runtime classpath — this module is not on one.

## The benchmarks

| Class | Question |
|---|---|
| `RowReadBenchmark` | The row-read path — the claim the design rests on. Two axes the design asks for by name: row count (the saving is proportional to it) and column count (`NarrowRow` = 4, `WideRow` = 12) |
| `DynamicSearchBenchmark` | Three `<if>` branches inside a `<where>`: `boolean` locals and a `StringBuilder` on one side, an OGNL-evaluated SqlNode tree on the other. `filters` selects how many branches are live, because that is what changes the interpreter's work |
| `SingleQueryBenchmark` | The honest half of the pitch: a single-row lookup, where the saving is a few hundred nanoseconds against a round trip. `transport=mem` vs `transport=tcp` |
| `StartupBenchmark` | One shot in a fresh JVM, so class loading counts — the builder, OGNL, XPath and the TypeHandler registry on one side; four generated classes on the other |
| `MegamorphicBenchmark` | 50 result classes vs one, per framework. **Generated** from `build.gradle.kts` |

## Fairness devices

The numbers are only worth something if the two sides are measuring the same
thing. Four deliberate choices, each of which would otherwise silently decide
the result:

- **`PinnedSession`.** MyBatis's `SqlSession` holds one connection until it is
  closed. Going through `JdbcLarkBatisSession` — which takes a connection per
  statement and gives it back — would put an H2 connect/disconnect inside every
  LarkBatis measurement and none of the MyBatis ones, making the comparison
  about connection pooling. Both sides are pinned to one connection. Nothing
  else changes: the generated `$$Impl` bodies are exactly the ones an
  application runs. `StartupBenchmark` uses the real session, because there
  connection setup *is* the thing being measured.
- **`localCacheScope = STATEMENT`.** MyBatis's first-level cache is on by
  default at SESSION scope, and every benchmark holds one session open for the
  whole trial. Left alone, the second `findById(1)` would never touch JDBC and
  the benchmark would measure a HashMap lookup. LarkBatis has no first-level
  cache to turn off.
- **`cacheEnabled = false`.** The second-level cache is a feature the design
  dropped; leaving it on compares two different feature sets.
- **`mapUnderscoreToCamelCase = true`.** LarkBatis applies it at build time,
  always. `created_at` has to reach `createdAt` on both sides, or the
  comparison is between a populated bean and a half-empty one.

Both sides run against **real H2 through a real JDBC driver**, so the driver's
own cost is inside both numbers. That makes the measured gap a *lower bound* on
the mapper-layer gap rather than an isolated one — a stubbed ResultSet would
flatter LarkBatis, and this is a benchmark that has to survive someone else
re-running it.

`DynamicSearchBenchmark` pins `id = #{pinnedId}` so **every setting returns
exactly one row**, and the setup asserts it. An earlier version let the row
count vary from 100 to 1 across settings, which made two of its three numbers a
restatement of the row-read result.

## The megamorphic experiment

The 50 result classes, the two mapper interfaces and the benchmark class are
**generated at configuration time** by `build.gradle.kts` into
`build/generated/sources/mega`. The point is 50 *distinct* classes with the
same shape, so every one of them is boilerplate by construction.

The mechanism under test: with many mappers, the call sites inside MyBatis's
`BeanWrapper` and `MethodInvoker` see many receiver types, the JIT stops
inlining them, and the gap widens. Generated readers have no shared call site
to pollute, so their two numbers should stay together.

`mono` calls one method 50 times; `mega` calls 50 methods once. The ratio
`mega/mono` is the megamorphic penalty — **compare it within a framework, not
across**. The 50 call sites are written out rather than looped over by
reflection, which would put the very cost being measured back into both sides.

## How to run

```bash
./gradlew :larkbatis-benchmarks:jmh                    # everything, ~25 min
./gradlew :larkbatis-benchmarks:jmh -Pquick            # smoke run, NOT publishable
./gradlew :larkbatis-benchmarks:jmh -Pbench=RowRead    # one class
./gradlew :larkbatis-benchmarks:jmh -PbenchJdk=21      # another JDK
```

> **The machine must be idle.** Never run a build, a test or the scanner while
> `jmh` is running — the numbers are the deliverable and any concurrent
> compilation corrupts them.

Results land in `build/reports/jmh/jmh-jdk<N>.json`. The task is never up to
date: the machine it ran on is an input Gradle cannot see, and re-running is
the entire point.

`-prof gc` is always on, so every result carries `gc.alloc.rate.norm` — bytes
allocated per operation. That is the answer to "how were the allocation numbers
obtained", and it is why this is JMH and not a hand-written loop.

JMH forks a JVM per benchmark and inherits the one running it, so `-PbenchJdk`
selects the JDK the numbers describe (default 17).
