# larkbatis-scanner

`larkbatis-scan` — point it at an existing MyBatis codebase, get back what
migrating it to LarkBatis would cost, statement by statement, with line
numbers.

Build-only, like the processor and the two build plugins. It does not depend on
`larkbatis-runtime` and must never reach an application's runtime classpath.

## What it does

It compiles nothing and resolves no dependencies. That is the design: the
question "would this even work for us" gets asked while holding a checkout that
has never been built, often on a machine that cannot build it.

It reads three things and reports on all of them:

- **mapper XML** — every construct, itemised with a line number
- **`mybatis-config.xml`** — settings, type handlers, and above all *which
  plugins are you running*
- **MyBatis-annotated Java** — annotations, method signatures, direct
  `SqlSession` calls

Nothing is rewritten. The report is the deliverable; the edits are yours.

## The verdict unit is the statement

Not the file, and not the finding. "893 of 1204 statements compile as they are"
is the sentence that decides whether a migration gets proposed — and one
`<bind>` in a 90-statement mapper should not condemn the other 89.

Findings are ranked by how much human judgement they need, because the first
question about a 300-mapper codebase is not "how many problems" but "how many
of them do I have to think about":

| Severity | Meaning |
|---|---|
| `BLOCKER` | No LarkBatis equivalent — the design dropped it. The mapper changes |
| `EDIT` | A rewrite with a known shape; the tool says exactly what to write |
| `REVIEW` | Supported, but only after someone decides how |
| `INFO` | Compiles as-is; worth knowing before it surprises someone |

Every rule carries a **topic** — a heading in
[`MIGRATION.md`](../MIGRATION.md) — so a report line traces back to a written
decision rather than to the tool's opinion. The topic points at the document
that ships next to the tool, because the person reading a report is holding
someone else's codebase and that is the only document they can be assumed to
have.

## How it works

```
source tree
   │
   ├─ XmlMapperScan       reads each mapper XML twice, on purpose:
   │                        ① a streaming walk itemises every construct with a
   │                           line number and keeps going past the first problem
   │                        ② MapperXmlParser — the frontend the processor
   │                           actually runs — decides whether the file would
   │                           load at all. Its diagnostic is only reported when
   │                           the walk found nothing that explains the rejection
   │
   ├─ JavaSourceScan      textual, and the report says so. Only files that
   │                      mention org.apache.ibatis are read past their imports,
   │                      so an ordinary service class cannot be a false positive.
   │                      A signature spanning several lines can be missed —
   │                      the counts are a floor, not a total
   │
   ├─ MyBatisConfigScan   mybatis-config.xml: settings, type handlers, plugins
   │
   ├─ SourceText          every file held as text with comments blanked to
   │                      spaces (not removed), so every character keeps its
   │                      line and column. A commented-out ${} is not work to do
   │
   └─ MigrationScan → List<Finding> → MigrationReport
                                       verdict first, catalogue second,
                                       line numbers third, guidance last
```

**The frontend is the oracle.** `XmlMapperScan` runs the same
`MapperXmlParser` and the same `ExprCompiler` grammar check that
`larkbatis-processor` runs at build time, so the report cannot drift away from
what actually compiles. Structure comes from the parser; positions come from
`SourceText`, because neither a DOM nor a StAX reader hands out a reliable line
number for a `${}` in the middle of a text node.

## How to run

From this repo:

```bash
./gradlew :larkbatis-scanner:run --args="/path/to/legacy-service"
./gradlew :larkbatis-scanner:run --args="--summary /path/to/legacy-service"
```

As a standalone command:

```bash
./gradlew :larkbatis-scanner:installDist
larkbatis-scanner/build/install/larkbatis-scan/bin/larkbatis-scan /path/to/service
```

In a project that already applies `larkbatis-gradle-plugin`, the same scan is
the `larkbatisScan` task.

```
usage: larkbatis-scan [options] <path>...

  --summary            counts only, no per-line detail
  --min=LEVEL          detail level: BLOCKER, EDIT, REVIEW, INFO (default REVIEW)
  --limit=N            most findings listed per file (default 40)
  --out=FILE           also write the report to FILE
  --fail-on-blocker    exit 1 when anything is blocked on a dropped feature
  -h, --help           this text
```

`--fail-on-blocker` makes it usable as a CI gate on a codebase mid-migration.

## Tests

```bash
./gradlew :larkbatis-scanner:test
```

`MigrationScanTest` runs the whole scan over the fixture tree in
`src/test/resources/scan/` — a legacy mapper XML, a `mybatis-config.xml` and an
annotated Java mapper (`.java.txt`, so it is not compiled).
