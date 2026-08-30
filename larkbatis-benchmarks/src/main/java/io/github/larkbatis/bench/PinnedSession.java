package io.github.larkbatis.bench;

import io.github.larkbatis.runtime.LarkBatisException;
import io.github.larkbatis.runtime.LarkBatisSession;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link LarkBatisSession} pinned to one connection for the life of a
 * benchmark trial.
 *
 * <p>This is the fairness device of the whole suite. MyBatis's
 * {@code SqlSession} holds one connection until it is closed, so if the
 * LarkBatis side went through {@code JdbcLarkBatisSession} — which calls
 * {@code dataSource.getConnection()} per statement and closes it again — every
 * measurement would include an H2 connect/disconnect on one side only, and the
 * comparison would be about connection pooling rather than about mapper code.
 * Pinning both sides to one connection takes that term out of both numbers.
 *
 * <p>Nothing else changes: the generated {@code $$Impl} bodies are exactly the
 * ones an application runs, and {@code conn()}/{@code release()} is precisely
 * the seam that lets a pool or a transaction manager own the connection. {@code StartupBenchmark} uses the real
 * {@code JdbcLarkBatisSession}, because there connection setup is part of
 * what is being measured.
 */
public final class PinnedSession implements LarkBatisSession {

    private final Connection connection;

    public PinnedSession(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection conn() {
        return connection;
    }

    @Override
    public void release(Connection c) {
        // the trial owns it
    }

    @Override
    public RuntimeException translate(SQLException e, String sql) {
        return new LarkBatisException("benchmark SQL failure: " + e.getMessage(), sql, e);
    }
}
