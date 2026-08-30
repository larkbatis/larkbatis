# LarkBatis vs MyBatis — measured

The design document set targets and marked four things explicitly unmeasured:
startup time, single-query latency against a real database, megamorphic
behaviour, and the hygiene needed to make any of it credible (how many bean
types, how many columns, JMH or a hand loop, which JDK). This file replaces the
targets with measurements and answers all four. One of the answers contradicts
what the design expected.

Reproduce with:

```bash
./gradlew :larkbatis-benchmarks:jmh                 # full run, ~25 min
./gradlew :larkbatis-benchmarks:jmh -PbenchJdk=17   # a different JDK
./gradlew :larkbatis-benchmarks:jmh -Pbench=RowRead # one class
```

The machine must be otherwise idle. A concurrent Gradle build is enough to
corrupt the numbers, which is how the first run of this suite was wasted.

---

## Method

| | |
|---|---|
| Harness | JMH 1.37, 2 forks × 5 warmup × 5 measurement iterations of 1 s |
| Allocation | `-prof gc` → `gc.alloc.rate.norm`, bytes per operation |
| MyBatis | 3.5.19, configured programmatically |
| Database | H2 2.3.232, in process except where marked `tcp` |
| JDK | **Temurin 21.0.12.1** for every table below; 17.0.20.1 for the JDK comparison |
| Machine | Apple M5 Pro, 15 cores, macOS 26.6.2 |
| Result shapes | `NarrowRow` = 4 columns, `WideRow` = 12 columns, `MegaBean00..49` = 6 columns |

Numbers are quoted on the **newer** JDK on purpose. MyBatis's reflective path
got measurably faster after JEP 416, so quoting JDK 17 would overstate the case
by about a fifth — see the JDK section below.

Three choices decide whether this comparison means anything, so they are stated
rather than buried:

**Both sides run against a real JDBC driver.** A stubbed `ResultSet` would
isolate the mapper layer and flatter LarkBatis. H2's own cost is inside both
numbers, which makes every gap below a *lower bound* on the mapper-layer gap.

**Both sides hold one connection for the whole trial.** MyBatis's `SqlSession`
does this naturally; the LarkBatis side uses a `PinnedSession` so it does too.
Otherwise every LarkBatis measurement would carry an H2 connect and disconnect
that the MyBatis side never pays, and the benchmark would be about connection
pooling. `StartupBenchmark` is the exception — there, connection setup is part
of what is being measured, and it uses the real `JdbcLarkBatisSession`.

**MyBatis's first-level cache is turned down to `STATEMENT` scope.** It
defaults to `SESSION` scope, and these benchmarks hold one session open, so the
second `findById(1)` would return a cached object without touching JDBC at all.
Left alone, that one default turns the MyBatis column into a HashMap lookup.
The second-level cache is off, because LarkBatis dropped it and comparing
different feature sets is not a comparison.

---

## Reading rows — the core claim

`findAll()` over the whole table.

| Rows | Columns | MyBatis | LarkBatis | Time | Allocation |
|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 1.48 µs | 0.36 µs | **−75%** | 6.6 KB → 1.8 KB (**−73%**) |
| 1 | 12 | 3.29 µs | 0.43 µs | **−87%** | 10.7 KB → 1.9 KB (**−83%**) |
| 100 | 4 | 17.1 µs | 3.23 µs | **−81%** | 67.6 KB → 10.2 KB (**−85%**) |
| 100 | 12 | 39.4 µs | 5.78 µs | **−85%** | 107.0 KB → 19.1 KB (**−82%**) |
| 10 000 | 4 | 1.53 ms | 0.29 ms | **−81%** | 6.77 MB → 1.05 MB (**−84%**) |
| 10 000 | 12 | 3.38 ms | 0.54 ms | **−84%** | 10.2 MB → 1.88 MB (**−82%**) |

Per row at 10 000 rows and 12 columns: **338 ns → 54 ns**, and **1 018 B →
188 B**.

Two things to take from this.

**The design's POC numbers hold up.** The POC measured −72% time and −88%
allocation on a 10 000-row query; this suite, measuring LarkBatis itself with
a real driver in both columns and on a JDK that favours MyBatis, gets −84% and
−82%.

**The saving scales with columns, not just rows.** Going from 4 to 12 columns
roughly doubles MyBatis's per-row cost and barely moves LarkBatis's, because
MyBatis pays a `PropertyTokenizer`, an `Object[]`, a map lookup and a reflective
call *per column*, while the generated reader pays a `getX` and a `putfield`.
That is why the column count is stated everywhere in this file.

---

## A single-row lookup — the honest half

The claim a migration proposal loses credibility over. `findById(7)`, one row,
four columns.

| Transport | MyBatis | LarkBatis | Time | Allocation |
|---|---:|---:|---:|---:|
| in process | 1.45 µs | 0.36 µs | −75% | 6.6 KB → 1.6 KB (−75%) |
| H2 over TCP (loopback) | 94.2 µs ± 1.7 | 89.2 µs ± 1.0 | **−5%** | 8.9 KB → 4.0 KB (−55%) |

**With a real round trip in the way, the mapper layer stops mattering.** Five
percent, and loopback TCP is the *cheapest* possible round trip — a database on
another host is slower still, so the real-world difference is smaller than this,
not larger.

Say this out loud when proposing a migration: **LarkBatis is an investment for
reporting queries, exports, batch jobs and list screens. It changes almost
nothing for single-record lookups.** The allocation saving survives (−55%) and
still matters for GC pressure under load, but the latency saving does not.

---

## Dynamic SQL

Three `<if>` branches inside a `<where>`. **Every setting returns exactly one
row** — the statement pins `id = #{pinnedId}` and the optional predicates are
all non-restrictive, so row reading is a constant and the only thing that
varies is how much SQL gets assembled. The setup asserts the row count, because
the first version of this benchmark let it vary from 100 to 1 and two of its
three numbers were really the row-read result wearing a different label.

| Live branches | MyBatis | LarkBatis | Time | Allocation |
|---|---:|---:|---:|---:|
| none | 2.15 µs | 0.41 µs | **−81%** | 10.4 KB → 2.1 KB (−79%) |
| one | 3.60 µs | 1.48 µs | **−59%** | 15.9 KB → 6.9 KB (−56%) |
| all three | 4.86 µs | 2.14 µs | **−56%** | 18.4 KB → 7.8 KB (−57%) |

The shape of that column is the interesting part. With no branch live,
LarkBatis has nothing to assemble — the statement is a constant `String`, and
it wins by 81%. As branches come alive it has to do `StringBuilder` work too,
and the gap settles at about 56%: that is the honest steady-state cost of
dynamic SQL once both sides are actually building a string.

**The design's most counter-intuitive finding survives.** The POC measured −45%
on the dynamic path against −73% on the row-read path and called the ordering
surprising. Measured properly here: **−56% against −84%**. The dynamic path
does gain proportionally less. Assembling SQL is string work both sides have to
do, and the interpreter's overhead on top of it — walking a SqlNode tree,
evaluating OGNL — is smaller than the per-column reflection overhead the
row-read path removes.

---

## Startup

Cold JVM, one shot per fork, 10 forks. Both sides bring up the same
application: four mapper interfaces (one carrying 50 statements), one mapper
XML, and one real query at the end so neither can be "started" without having
done the work.

Quoted on **JDK 17**, the only run whose error bars are tight enough to mean
anything — the JDK 21 run measured MyBatis at 76.9 ± 41.3 ms, which says
nothing except that single-shot cold measurements are noisy.

| | MyBatis | LarkBatis |
|---|---:|---:|
| Cold start to first row | 61.8 ms ± 3.7 | **6.3 ms ± 0.8** |
| Allocation | 27.0 MB | 15.8 MB |

**−90%, and it was completely unmeasured before.** The 55 ms MyBatis spends is
XML parsing, `Reflector` construction, the type-handler registry, the OGNL and
XPath machinery being class-loaded, and `MappedStatement` construction for 51
statements. LarkBatis does none of it: the work happened during `javac`, and
starting up means loading four generated classes.

The design suspected this would be real but drowned by Spring context creation
and connection-pool warmup in a real application. That caveat still stands —
this is the mapper layer alone. But 55 ms is not noise in a serverless or
native-image context, which is exactly where it is claimed to matter.

---

## Megamorphic behaviour — the design was wrong

The design predicted that with hundreds of mappers, the call sites inside
MyBatis's `BeanWrapper` and `MethodInvoker` would go megamorphic, the JIT would
stop inlining them, and the gap would widen beyond what the POC measured. It
called this "not measured at all; an argument about inlining."

50 single-row reads of a 6-column table. `mono` reads one result class 50
times; `mega` reads 50 different result classes once each.

| | MyBatis | LarkBatis | LarkBatis advantage |
|---|---:|---:|---:|
| monomorphic (1 bean type) | 103.5 µs | 22.8 µs | 4.54× |
| megamorphic (50 bean types) | 123.4 µs | 26.2 µs | 4.71× |
| megamorphic penalty | **+19.3%** | **+15.0%** | |

**The mechanism is real but the effect is small.** MyBatis does pay more for 50
types than for one — 19% — but LarkBatis pays 15% for the same change, and the
advantage widens only from 4.54× to 4.71×. That is under 4%, not the
qualitative shift the design expected.

Allocation is the giveaway: 394 KB vs 398 KB for MyBatis, 100.4 KB vs 100.8 KB
for LarkBatis. Nothing changes. Whatever megamorphic dispatch costs here, it
costs it in inlining and branch prediction, not in extra work — and MyBatis's
per-column allocation, which is the actual bulk of its cost, is identical
either way.

**Conclusion to carry forward: do not use "it gets worse at scale" as an
argument for LarkBatis.** The honest claim is that the advantage is already
large at one bean type and stays roughly constant. That is a better argument
anyway, because it does not depend on the reader's codebase being big.

---

## JDK 17 vs JDK 21 — the question the design raised

The design asked for the same suite on two JDKs straddling JEP 416, which put
core reflection on method handles and made `Method.invoke` substantially
faster. It named JDK 11 and 21; LarkBatis requires 17, so 11 is impossible.
JEP 416 landed in **18**, so 17 vs 21 straddles the same change.

The effect is real, and it is one-sided.

| | JDK 17 | JDK 21 | change |
|---|---:|---:|---:|
| MyBatis, 10 000 × 4 cols | 2.13 ms | 1.53 ms | **−28%** |
| MyBatis, 10 000 × 12 cols | 3.95 ms | 3.38 ms | **−14%** |
| MyBatis, 100 × 4 cols | 22.8 µs | 17.1 µs | **−25%** |
| LarkBatis, 10 000 × 4 cols | 0.302 ms | 0.289 ms | −4% |
| LarkBatis, 10 000 × 12 cols | 0.582 ms | 0.541 ms | −7% |
| LarkBatis, 100 × 4 cols | 3.56 µs | 3.23 µs | −9% |

**MyBatis gets meaningfully faster on a newer JDK; LarkBatis barely moves.**
That is what the mechanism predicts — MyBatis's per-column path ends in
`Method.invoke`, which JEP 416 sped up, while generated code has no reflection
to accelerate. Allocation moved the same way (MyBatis 9.00 → 6.77 MB per 10 000
narrow rows).

So the advantage narrows on newer JDKs. On the 10 000 × 4 workload it is
**7.1× on JDK 17 and 5.3× on JDK 21**; in percentage terms, −86% and −81%.

**Always say which JDK.** Publishing the JDK 17 figures without that would
overstate the case by about a fifth, and the first person to re-run the suite on
a current JDK would find out. Every table above this section is JDK 21 for
exactly that reason.

---

## What is not measured here

- **GraalVM native image.** Still not built — the development machine has no
  GraalVM. This is the project's strongest qualitative claim and it remains
  unverified. Do not present it as a result.
- **A real database over a real network.** H2 over loopback TCP is a genuine
  socket and wire protocol, but it is not PostgreSQL on another host. It is
  used as a lower bound on round-trip cost, and the single-query conclusion is
  the direction a slower database only strengthens.
- **Concurrency.** Every benchmark is single-threaded. Contention, pool
  behaviour and GC under parallel load are untested.
- **Build time.** The cost side of the trade — code generation running in javac
  on every developer's machine — has not been measured on a large codebase.
