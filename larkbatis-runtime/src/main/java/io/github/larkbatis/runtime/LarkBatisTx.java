package io.github.larkbatis.runtime;

/**
 * One transaction scope, intended for try-with-resources:
 *
 * <pre>{@code
 * try (LarkBatisTx tx = session.begin()) {
 *     mapper.insert(user);
 *     tx.commit();
 * }
 * }</pre>
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #commit()} votes; the actual commit happens when the outermost
 *       scope closes. Leaving any scope without committing marks the whole
 *       transaction rollback-only — rollback is the safe default.</li>
 *   <li>Scopes nest: inner {@code begin()} joins the outer transaction and
 *       only the outermost close touches the connection.</li>
 *   <li>Committing a rollback-only transaction throws
 *       {@link LarkBatisRollbackOnlyException} instead of silently
 *       persisting half of the work.</li>
 * </ul>
 */
public final class LarkBatisTx implements AutoCloseable {

    private final JdbcLarkBatisSession session;
    private final JdbcLarkBatisSession.TxScope scope;
    private final boolean outermost;
    private boolean committed;
    private boolean closed;

    LarkBatisTx(JdbcLarkBatisSession session, JdbcLarkBatisSession.TxScope scope, boolean outermost) {
        this.session = session;
        this.scope = scope;
        this.outermost = outermost;
    }

    /**
     * Vote to commit this scope. Blows up immediately if an inner scope has
     * already poisoned the transaction — committing here would be wrong.
     */
    public void commit() {
        if (closed) {
            throw new IllegalStateException("Transaction scope already closed");
        }
        if (scope.rollbackOnly) {
            throw new LarkBatisRollbackOnlyException();
        }
        committed = true;
    }

    /** Explicitly poison the transaction; the outermost close will roll back. */
    public void rollbackOnly() {
        scope.rollbackOnly = true;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!committed) {
            scope.rollbackOnly = true;
        }
        if (!outermost) {
            return; // the handle that opened the scope is the one that ends it
        }
        session.finish(scope, committed && !scope.rollbackOnly);
    }
}
