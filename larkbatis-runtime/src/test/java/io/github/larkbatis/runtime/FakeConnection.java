package io.github.larkbatis.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A fake {@link Connection} that records state and can be told to fail on
 * demand — the "FaultyDataSource" trick: it turns the four hardest
 * transaction tests from "provision a broken database" into plain assertions. Proxy-based; reflection is fine in test code, it is the
 * runtime and generated code that must stay reflection-free.
 */
final class FakeConnection {

    final Connection connection;

    boolean autoCommit = true;
    boolean readOnly;
    boolean closed;
    int commitCount;
    int rollbackCount;

    boolean failOnCommit;
    boolean failOnRollback;
    boolean failOnSetAutoCommit;
    boolean failOnSetReadOnly;
    boolean failOnClose;

    /** Connection state snapshotted the moment close() is called. */
    Boolean autoCommitAtClose;
    Boolean readOnlyAtClose;

    FakeConnection() {
        connection = (Connection) Proxy.newProxyInstance(
                FakeConnection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                this::invoke);
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getAutoCommit":
                return autoCommit;
            case "setAutoCommit":
                if (failOnSetAutoCommit) {
                    throw new SQLException("setAutoCommit refused");
                }
                autoCommit = (Boolean) args[0];
                return null;
            case "isReadOnly":
                return readOnly;
            case "setReadOnly":
                if (failOnSetReadOnly) {
                    throw new SQLException("setReadOnly refused");
                }
                readOnly = (Boolean) args[0];
                return null;
            case "commit":
                if (failOnCommit) {
                    throw new SQLException("commit refused");
                }
                commitCount++;
                return null;
            case "rollback":
                if (failOnRollback) {
                    throw new SQLException("rollback refused");
                }
                rollbackCount++;
                return null;
            case "close":
                if (failOnClose) {
                    throw new SQLException("close refused");
                }
                closed = true;
                autoCommitAtClose = autoCommit;
                readOnlyAtClose = readOnly;
                return null;
            case "isClosed":
                return closed;
            case "toString":
                return "FakeConnection@" + Integer.toHexString(System.identityHashCode(this));
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                throw new UnsupportedOperationException(
                        "FakeConnection does not stub Connection." + method.getName());
        }
    }
}
