# larkbatis-conformance

The differential test harness: the same mapper, run through **MyBatis** and
through **LarkBatis-generated code**, with the two outputs compared. MyBatis
3.5.19 is the oracle — correct by definition.

This is the project's most important test asset. Every emitter change answers
to it.

Never published, and test-only: the module has no `src/main` at all. MyBatis
appears here as the comparison subject and must never touch a LarkBatis
runtime classpath.

## What it compares

For most suites, **not the rows — the JDBC calls**. Both sides run against a
`RecordingDataSource`, a proxy-based DataSource whose connections record what
the caller did instead of talking to a database:

1. the final SQL string handed to `prepareStatement` (and how generated keys
   were requested),
2. every `setX` call, **in order**,
3. with the JDBC type visible in the call name (`setLong`, `setString`, …).

Queries return zero rows, updates report one affected row. Thousands of
statements per second, no database anywhere. The idea comes from
`minibatis-poc/Demo.java` in the sibling MyBatis clone.

Reflection in the recorder is fine — it is the *runtime and generated code*
that must stay reflection-free, not the test tooling.

## The suites

| Suite | What it pins |
|---|---|
| `DifferentialTest` | Static SQL: the SQL string, the `setX` order and the JDBC type of every parameter must match **char for char** |
| `DynamicDifferentialTest` | The same mapper XML through MyBatis's `DynamicSqlSource` at runtime and through the LarkBatis build-time fold, on **every branch combination**. The oracle's SQL is whitespace-collapsed first; `?` positions, bind order, `setX` names and values must match exactly |
| `ForeachDifferentialTest` | `<foreach>`. MyBatis routes each element through a generated `__frch_item_N` binding in a HashMap and re-parses the assembled SQL to find it again; LarkBatis writes two loops over the same elements. This proves they arrive at the same SQL and the same `setX` sequence |
| `ResultMapDifferentialTest` | The one-level join — and this one compares the **result**, not the SQL. Both frameworks emit the same SELECT for a `<collection>`; the whole question is how the join's rows collapse back into objects. It needs real rows, so it runs on H2: the LEFT JOIN miss, a parent spanning several rows, child ordering |
| `XmlCorpusSweepTest` | Every mapper XML in the mybatis-3 clone through the real frontend, bucketed by outcome |

One intentional divergence has no fixture here: an empty `<foreach>` collection
throws instead of silently contributing nothing, and there is no oracle output
to compare that against — `ForeachMapperEndToEndTest` in `larkbatis-sample`
pins it instead.

## The corpus sweep

`XmlCorpusSweepTest` runs every mapper XML in `../mybatis-3/src/test/resources`
through `MapperXmlParser`, `DynamicLowering` (with permissive token/test stubs)
and `ExprCompiler.checkGrammar`. Outcomes are bucketed and counted; rejections
of deliberately dropped features are **expected**, not failures. The hard
assertion is that no corpus file makes the frontend fail with anything but its
own controlled diagnostics.

The typed half of the pipeline (bind resolution, result mapping) needs the
corpus's Java interfaces compiled and stays out of scope — the curated
differential suites cover that.

The test **skips** when the sibling clone is absent. The report is printed and
written to `build/reports/larkbatis/`.

## How to run

```bash
./gradlew :larkbatis-conformance:diffTest                      # the harness only
./gradlew :larkbatis-conformance:diffTest -Pstatement=<id>     # narrow to one statement
./gradlew :larkbatis-conformance:test                          # everything in the module
```

`diffTest` is the contract behind the `/diff-test` skill. It has its own result
and report directories on purpose: `test` and `diffTest` run the same classes,
and sharing `build/test-results/test` makes one clobber the other's XML when
both are in the same invocation.

Fixtures live in `src/test/java/.../fixtures/` (interfaces and result classes)
and `src/test/resources/.../fixtures/` (the XML). The XML sits on the test
classpath so MyBatis finds it as a mapper resource, and the build passes the
same directory to the processor via `-Alarkbatis.mapperDir` so LarkBatis
compiles the identical file.

## Versions

The executable oracle is `org.mybatis:mybatis:3.5.19` — the latest release on
Central. Ground-truth *reading* stays the sibling clone at `../mybatis-3`
(3.6.0-SNAPSHOT). When behaviour differs between the two, that is a finding
worth flagging rather than a test to adjust quietly.
