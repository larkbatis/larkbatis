package io.github.lightbatis.conformance;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A DataSource whose connections record what the caller does instead of
 * talking to a database (build plan §03, figure 2 — the idea originates in
 * minibatis-poc/Demo.java): ① the final SQL string handed to
 * prepareStatement (plus how keys were requested), ② every {@code setX} in
 * order, ③ with the JDBC type visible in the call name. Queries return zero
 * rows; updates report one affected row. Thousands of statements per second,
 * no database anywhere.
 *
 * <p>Proxy-based — reflection is fine in test tooling; it is the runtime and
 * generated code that must stay reflection-free.
 */
public final class RecordingDataSource implements DataSource {

    private final Recording recording;

    public RecordingDataSource(Recording recording) {
        this.recording = recording;
    }

    @Override
    public Connection getConnection() {
        return proxy(Connection.class, new ConnectionHandler());
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RecordingDataSource.class.getClassLoader(),
                new Class<?>[] {type}, handler);
    }

    /** Formats one bound value the way the comparison wants to read it. */
    private static String formatValue(Object value) {
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        return String.valueOf(value);
    }

    private final class ConnectionHandler implements java.lang.reflect.InvocationHandler {
        private boolean autoCommit = true;
        private boolean readOnly;
        private boolean closed;

        @Override
        public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "prepareStatement": {
                    String sql = (String) args[0];
                    String mode = "";
                    if (args.length == 2 && args[1] instanceof String[] columns) {
                        mode = "keyColumns=" + Arrays.toString(columns);
                    } else if (args.length == 2 && args[1] instanceof Integer flag
                            && flag == Statement.RETURN_GENERATED_KEYS) {
                        mode = "RETURN_GENERATED_KEYS";
                    }
                    Recording.Prepared prepared = recording.prepared(sql, mode);
                    return proxy(PreparedStatement.class,
                            new PreparedStatementHandler(proxyObject, prepared));
                }
                case "getAutoCommit": return autoCommit;
                case "setAutoCommit": autoCommit = (Boolean) args[0]; return null;
                case "isReadOnly": return readOnly;
                case "setReadOnly": readOnly = (Boolean) args[0]; return null;
                case "commit", "rollback", "clearWarnings": return null;
                case "close": closed = true; return null;
                case "isClosed": return closed;
                case "getWarnings": return null;
                case "getMetaData": return proxy(DatabaseMetaData.class, new MetaDataHandler());
                case "toString": return "RecordingConnection";
                case "equals": return proxyObject == args[0];
                case "hashCode": return System.identityHashCode(proxyObject);
                default:
                    throw new UnsupportedOperationException(
                            "RecordingDataSource does not stub Connection." + method.getName());
            }
        }
    }

    private static final class MetaDataHandler implements java.lang.reflect.InvocationHandler {
        @Override
        public Object invoke(Object proxyObject, Method method, Object[] args) {
            return switch (method.getName()) {
                case "supportsMultipleResultSets" -> false;
                case "getDatabaseProductName" -> "RecordingDataSource";
                case "getDatabaseProductVersion" -> "0";
                default -> throw new UnsupportedOperationException(
                        "RecordingDataSource does not stub DatabaseMetaData." + method.getName());
            };
        }
    }

    private final class PreparedStatementHandler implements java.lang.reflect.InvocationHandler {
        private final Object connection;
        private final Recording.Prepared prepared;
        private int batchCount;

        PreparedStatementHandler(Object connection, Recording.Prepared prepared) {
            this.connection = connection;
            this.prepared = prepared;
        }

        @Override
        public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer index) {
                if (name.equals("setNull")) {
                    prepared.bind("setNull(" + index + ", " + JdbcTypeNames.of((Integer) args[1]) + ")");
                } else if (name.equals("setFetchSize") || name.equals("setQueryTimeout")
                        || name.equals("setMaxRows")) {
                    return null; // tuning knobs, not bindings
                } else {
                    prepared.bind(name + "(" + index + ", " + formatValue(args[1]) + ")");
                }
                return null;
            }
            switch (name) {
                case "execute": return true;
                case "executeQuery": return emptyResultSet();
                case "executeUpdate": return 1;
                case "addBatch": batchCount++; prepared.bind("addBatch()"); return null;
                case "executeBatch": {
                    int[] counts = new int[batchCount];
                    Arrays.fill(counts, 1);
                    return counts;
                }
                case "getResultSet": return emptyResultSet();
                case "getUpdateCount": return 1;
                case "getMoreResults": return false;
                case "getGeneratedKeys": return emptyResultSet();
                case "getConnection": return connection;
                case "close", "clearParameters", "clearWarnings", "cancel": return null;
                case "getWarnings": return null;
                case "isClosed": return false;
                case "toString": return "RecordingPreparedStatement[" + prepared.sql() + "]";
                case "equals": return proxyObject == args[0];
                case "hashCode": return System.identityHashCode(proxyObject);
                default:
                    throw new UnsupportedOperationException(
                            "RecordingDataSource does not stub PreparedStatement." + name);
            }
        }
    }

    private static ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (proxyObject, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "close" -> null;
            case "isClosed" -> false;
            case "wasNull" -> false;
            case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
            case "getMetaData" -> proxy(ResultSetMetaData.class,
                    (p, m, a) -> switch (m.getName()) {
                        case "getColumnCount" -> 0;
                        default -> throw new UnsupportedOperationException(
                                "RecordingDataSource does not stub ResultSetMetaData." + m.getName());
                    });
            case "toString" -> "EmptyResultSet";
            default -> throw new UnsupportedOperationException(
                    "RecordingDataSource does not stub ResultSet." + method.getName());
        });
    }

    // --- DataSource boilerplate ---------------------------------------------------

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
        return Logger.getLogger("RecordingDataSource");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("unwrap not supported");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
