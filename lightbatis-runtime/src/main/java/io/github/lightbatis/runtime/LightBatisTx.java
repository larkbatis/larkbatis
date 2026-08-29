package io.github.lightbatis.runtime;

/**
 * One transaction scope, intended for try-with-resources:
 *
 * <pre>{@code
 * try (LightBatisTx tx = session.begin()) {
 *     mapper.insert(user);
 *     tx.commit();
 * }
 * }</pre>
 *
 * <p>Semantics (build plan §05):
 * <ul>
 *   <li>{@link #commit()} votes; the actual commit happens when the outermost
 *       scope closes. Leaving any scope without committing marks the whole
 *       transaction rollback-only — rollback is the safe default.</li>
 *   <li>Scopes nest: inner {@code begin()} joins the outer transaction and
 *       only the outermost close touches the connection.</li>
 *   <li>Committing a rollback-only transaction throws
 *       {@link LightBatisRollbackOnlyException} instead of silently
 *       persisting half of the work.</li>
 * </ul>
 */
public final class LightBatisTx implements AutoCloseable {

    private final JdbcLightBatisSession session;
    private final JdbcLightBatisSession.TxScope scope;
    private final boolean outermost;
    private boolean committed;
    private boolean closed;

    LightBatisTx(JdbcLightBatisSession session, JdbcLightBatisSession.TxScope scope, boolean outermost) {
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
            throw new LightBatisRollbackOnlyException();
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
            scope.depth--;
            return;
        }
        session.finish(scope, committed && !scope.rollbackOnly);
    }
}
