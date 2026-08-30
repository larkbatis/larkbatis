# larkbatis-runtime

The thin JDBC layer generated mapper code calls into. ~1,000 lines of main
source, **zero dependencies beyond the JDK** (`java.sql` + `java.logging`), no
reflection, no proxies, no service loading, no classpath scanning.

Published: `io.github.larkbatis:larkbatis-runtime`. This is the only
LarkBatis module that ships in an application's runtime image.

## What it does

Four things, and deliberately no more:

1. **Hands out a Connection and takes it back** — `LarkBatisSession`.
2. **Owns a transaction scope** — `LarkBatisTx` (standalone JDBC only; under
   Spring the transaction is Spring's).
3. **Moves values across JDBC** — `JdbcCodec`, for the cases plain
   `rs.getX()` / `ps.setX()` cannot express (nullable primitives, `java.time`,
   enums), plus `LarkBatisTypeHandler` for the types an application brings of
   its own.
4. **Gates raw SQL text** — `SqlFragment`, plus the `LarkBatisSql` counters
   that notice when a statement's SQL text stops being bounded.

Everything else that MyBatis does at runtime — parsing SQL, choosing a
TypeHandler, mapping columns to setters, evaluating OGNL — already happened at
build time, so there is no code here to do it. Note the difference on handlers:
a custom handler still *runs* at run time, because moving a value is value
work. Deciding *which* one runs is not, and that decision is gone.

## The API

| Type | Role |
|---|---|
| `LarkBatisSession` | Interface: `conn()`, `release(c)`, `translate(e, sql)`. Also carries the default methods behind `Stream` returns and the manual escape hatch |
| `JdbcLarkBatisSession` | The standalone implementation over a `DataSource`, with a thread-bound transaction scope |
| `LarkBatisTx` | One transaction scope, for try-with-resources |
| `RowReader<T>` | `T read(ResultSet)` — implementations are generated, one per result class |
| `StatementBinder` | `bind(PreparedStatement)` — used by the escape hatch, where the call site knows its own parameter positions |
| `JdbcCodec` | Static get/set helpers chosen at build time by `ValueKind` |
| `LarkBatisTypeHandler<J>` | `read(ResultSet, int)` / `write(PreparedStatement, int, J)` — a custom handler named by `@Handler` or a mapper XML `typeHandler`. Stateless, public no-arg constructor, owns `null` in both directions |
| `SqlFragment` | `allowed(...)`, `identifier(...)`, `unsafeRawSql(...)` — the only way raw text reaches SQL |
| `LarkBatisSql` | `trackVariants`, `padPow2`, `sum(int[])`, and the two variant-tracking knobs |
| `LarkBatisException` + subclasses | The unchecked exception tree, each carrying the SQL text that was executing |

## How it works

### Connection lifecycle — `conn()` / `release()`, never try-with-resources

Generated bodies borrow a Connection and return it in a `finally`:

```java
Connection c = s.conn();
try (PreparedStatement ps = c.prepareStatement(SQL_findById)) {
    ...
} catch (SQLException e) {
    throw s.translate(e, SQL_findById);
} finally {
    s.release(c);
}
```

The Connection is deliberately **not** in the try-with-resources. Under a
managed transaction, closing it is wrong — only `release` knows whether this
connection really belongs to the caller. `JdbcLarkBatisSession.release` is a
no-op when the connection is the current transaction's; `SpringLarkBatisSession`
(in the `larkbatis-spring` repo) delegates the same decision to
`DataSourceUtils`.

### Transactions — vote to commit, rollback is the default

```java
try (LarkBatisTx tx = session.begin()) {
    mapper.insert(user);
    tx.commit();          // a vote, not the commit
}                          // the outermost close does the commit
```

- Scopes nest. An inner `begin()` joins the outer transaction; only the
  outermost close touches the connection.
- Leaving any scope without calling `commit()` marks the whole transaction
  rollback-only.
- Committing an already-poisoned transaction throws
  `LarkBatisRollbackOnlyException` rather than persisting half the work.
- `finish()` unbinds the thread **first**, unconditionally: a connection dying
  mid-cleanup must never leave a stale scope on a pooled thread.

Borrow-nesting (`conn`/`release`) and transaction-nesting (`begin`/`close`)
share the class but not a counter — a missing `release` on some exit path must
never keep a transaction from committing.

### `Stream` returns — the caller owns the resources

`Stream`-returning mapper methods are the one generated shape whose JDBC
resources outlive the method that opened them. `ResultSetStream` wraps the open
cursor in a sequential `Stream` whose `onClose` releases ResultSet, statement
and Connection in reverse order, letting the first failure win. The failure
path before the stream exists is `streamFailed(...)`, which translates and then
undoes by hand, suppressing any cleanup failure into the real one.

```java
try (Stream<User> rows = mapper.streamAll()) { ... }   // closing is not optional
```

### `${}` discipline — `SqlFragment` and variant tracking

A `String` never reaches SQL text. `SqlFragment.allowed(value, ...)` bounds the
variants by the whitelist, `identifier(value)` accepts `name` or `schema.name`,
and `unsafeRawSql(value)` validates nothing — its name is ugly on purpose, so
`grep -rn unsafeRawSql src/` lists every injection point in a codebase.

Because statement caches (the driver's and the database's) are keyed by SQL
text, generated code calls `LarkBatisSql.trackVariants(statementId, sql)` for
every statement whose text is not fixed. Crossing the threshold (default 64)
logs once per statement — or throws `LarkBatisUnboundedVariantsException` when
`failOnUnboundedVariants` is on, which is a staging/test setting, not a
production one.

### Exception translation

`JdbcLarkBatisSession.translate` builds a message carrying SQLState, vendor
error code, the driver message and the SQL text, and wraps it in
`LarkBatisException`. Transaction plumbing uses pseudo-statement ids
(`"tx:commit"`, `"connection:acquire"`) so the same field always says what was
running. The Spring implementation instead routes through
`SQLExceptionTranslator`, so mappers fail with the `DuplicateKeyException` a
service already catches.

## The escape hatch

When a call site really must assemble its own SQL, it still goes through the
audited gate and still reads rows with a generated reader — so no reflection is
involved and the result type stays compile-checked:

```java
default List<User> recent(LarkBatisSession s, int limit) {
    return s.query(
        SqlFragment.unsafeRawSql("SELECT id, name, email, created_at FROM users ORDER BY id DESC LIMIT ?"),
        ps -> ps.setInt(1, limit),
        UserRow.READER);            // generated
}
```

`query`, `queryOne`, `queryStream` and `update` are default methods on
`LarkBatisSession`.

## How to run

```bash
./gradlew :larkbatis-runtime:test
./gradlew :larkbatis-runtime:test --tests "io.github.larkbatis.runtime.SqlFragmentTest"
```

The test source tree holds the **hand-written emitter spec**:
`src/test/java/io/github/larkbatis/runtime/handwritten/UserMapper$$Impl.java`
is what generated code must look like, written by hand and executed against H2.
When an emitter change makes generated output diverge from this file, one of
the two is wrong — decide which before touching the golden snapshots in
`larkbatis-processor`.

## JPMS

```java
module io.github.larkbatis.runtime {
    requires transitive java.sql;   // this module's API *is* java.sql types
    requires java.logging;
    exports io.github.larkbatis.runtime;
}
```

`java.sql` is transitive because `conn()` hands back a `Connection` and
`RowReader` takes a `ResultSet` — a consumer naming types we gave them should
not have to re-declare the edge. There is no `opens` and no `uses`: nothing
here reflects, and there is no ServiceLoader. A descriptor that appears to need
`opens` means a reflection leak to find, not a directive to add.
