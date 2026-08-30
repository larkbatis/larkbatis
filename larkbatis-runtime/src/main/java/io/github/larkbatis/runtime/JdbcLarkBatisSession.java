package io.github.larkbatis.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Standalone {@link LarkBatisSession} on plain JDBC, with a thread-bound
 * transaction scope.
 *
 * <p>Two nesting mechanisms share this class and deliberately do not share a
 * counter: {@link #conn()}/{@link #release} borrow a connection within one
 * method body, while {@link #begin()}/{@link LarkBatisTx#close()} nest
 * transaction scopes. Only {@code begin()} counts — a missing {@code release}
 * on some exit path must never keep a transaction from committing.
 *
 * <p>Each session owns its own thread binding, so two sessions on two
 * DataSources are independent even on the same thread.
 */
public final class JdbcLarkBatisSession implements LarkBatisSession {

    private final DataSource dataSource;
    private final ThreadLocal<TxScope> currentTx = new ThreadLocal<>();

    public JdbcLarkBatisSession(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Connection conn() {
        TxScope scope = currentTx.get();
        if (scope != null) {
            return scope.connection;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw translate(e, "connection:acquire");
        }
    }

    @Override
    public void release(Connection c) {
        TxScope scope = currentTx.get();
        if (scope != null && scope.connection == c) {
            return; // the transaction owns it; LarkBatisTx.close() will clean up
        }
        try {
            c.close();
        } catch (SQLException e) {
            throw translate(e, "connection:release");
        }
    }

    @Override
    public RuntimeException translate(SQLException e, String sql) {
        StringBuilder message = new StringBuilder("SQL failure");
        if (e.getSQLState() != null) {
            message.append(" [SQLState ").append(e.getSQLState()).append(']');
        }
        if (e.getErrorCode() != 0) {
            message.append(" [error ").append(e.getErrorCode()).append(']');
        }
        message.append(": ").append(e.getMessage()).append(" — while executing: ").append(sql);
        return new LarkBatisException(message.toString(), sql, e);
    }

    /** Open (or join) a read-write transaction scope on the current thread. */
    public LarkBatisTx begin() {
        return begin(false);
    }

    /**
     * Open (or join) a transaction scope on the current thread. The
     * {@code readOnly} flag only applies to the outermost scope; joining an
     * existing scope leaves the connection as it is.
     */
    public LarkBatisTx begin(boolean readOnly) {
        TxScope existing = currentTx.get();
        if (existing != null) {
            return new LarkBatisTx(this, existing, false);
        }
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            throw translate(e, "tx:begin");
        }
        TxScope scope = new TxScope(c);
        try {
            scope.previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            if (readOnly) {
                scope.previousReadOnly = c.isReadOnly();
                c.setReadOnly(true);
                scope.readOnlyChanged = true;
            }
        } catch (SQLException e) {
            RuntimeException primary = translate(e, "tx:begin");
            try {
                c.close();
            } catch (SQLException closeFailure) {
                primary.addSuppressed(closeFailure);
            }
            throw primary;
        }
        currentTx.set(scope);
        return new LarkBatisTx(this, scope, true);
    }

    /** Whether a transaction scope is bound to the current thread. */
    public boolean hasActiveTransaction() {
        return currentTx.get() != null;
    }

    /**
     * Ends the outermost scope: commit or roll back, restore the connection,
     * close it, and unbind the thread — in that priority order reversed. The
     * thread is unbound FIRST, unconditionally: a connection dying mid-cleanup
     * must never leave a stale scope poisoning a pooled thread.
     */
    void finish(TxScope scope, boolean commit) {
        currentTx.remove();
        Connection c = scope.connection;
        RuntimeException primary = null;
        try {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
        } catch (SQLException e) {
            primary = translate(e, commit ? "tx:commit" : "tx:rollback");
        }
        try {
            c.setAutoCommit(scope.previousAutoCommit);
        } catch (SQLException e) {
            primary = addOrPrimary(primary, e, "tx:restoreAutoCommit");
        }
        if (scope.readOnlyChanged) {
            try {
                c.setReadOnly(scope.previousReadOnly);
            } catch (SQLException e) {
                primary = addOrPrimary(primary, e, "tx:restoreReadOnly");
            }
        }
        try {
            c.close();
        } catch (SQLException e) {
            primary = addOrPrimary(primary, e, "tx:close");
        }
        if (primary != null) {
            throw primary;
        }
    }

    /** addSuppressed discipline for cleanup paths. */
    private RuntimeException addOrPrimary(RuntimeException primary, SQLException e, String stage) {
        if (primary == null) {
            return translate(e, stage);
        }
        primary.addSuppressed(e);
        return primary;
    }

    /** One thread-bound transaction; shared by every nested LarkBatisTx handle. */
    static final class TxScope {
        final Connection connection;
        boolean rollbackOnly;
        boolean previousAutoCommit = true;
        boolean previousReadOnly;
        boolean readOnlyChanged;

        TxScope(Connection connection) {
            this.connection = connection;
        }
    }
}
