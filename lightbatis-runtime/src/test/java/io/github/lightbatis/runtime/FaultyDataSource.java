package io.github.lightbatis.runtime;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Hands out {@link FakeConnection}s and remembers every one it created. */
final class FaultyDataSource implements DataSource {

    final List<FakeConnection> connections = new ArrayList<>();
    boolean failOnGetConnection;
    /** Arms failOnSetAutoCommit on the next connection handed out. */
    boolean failNextSetAutoCommit;

    FakeConnection last() {
        return connections.get(connections.size() - 1);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (failOnGetConnection) {
            throw new SQLException("no connection available");
        }
        FakeConnection fake = new FakeConnection();
        if (failNextSetAutoCommit) {
            fake.failOnSetAutoCommit = true;
            failNextSetAutoCommit = false;
        }
        connections.add(fake);
        return fake.connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("FaultyDataSource");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException("unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
