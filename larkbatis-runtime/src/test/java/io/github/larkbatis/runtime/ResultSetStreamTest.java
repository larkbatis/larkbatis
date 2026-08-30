package io.github.larkbatis.runtime;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The close path of a streaming statement. The happy path is covered end to
 * end against H2 in larkbatis-sample; what only a fake can show is what
 * happens when a close itself fails — the Connection must still go back, and
 * the first failure must survive rather than being replaced by the second.
 */
class ResultSetStreamTest {

    private final List<String> events = new ArrayList<>();

    @Test
    void closesInReverseOrderOfAcquisition() {
        RecordingSession session = new RecordingSession();
        ResultSetStream.close(session, connection(), statement(false), resultSet(false), "SELECT 1");
        assertEquals(List.of("rs.close", "ps.close", "release"), events);
    }

    @Test
    void aFailingResultSetStillReleasesTheConnection() {
        RecordingSession session = new RecordingSession();
        LarkBatisException thrown = assertThrows(LarkBatisException.class,
                () -> ResultSetStream.close(session, connection(), statement(false),
                        resultSet(true), "SELECT 1"));
        assertEquals("SELECT 1", thrown.sql());
        assertTrue(events.contains("release"), "the connection was not released: " + events);
    }

    @Test
    void theFirstFailureWinsAndTheSecondIsSuppressed() {
        RecordingSession session = new RecordingSession();
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ResultSetStream.close(session, connection(), statement(true),
                        resultSet(true), "SELECT 1"));
        // rs closes first, so its failure is the primary one
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof SQLException);
        assertTrue(thrown.getMessage().contains("rs.close refused"));
    }

    @Test
    void streamFailedTranslatesAndCleansUp() {
        RecordingSession session = new RecordingSession();
        SQLException cause = new SQLException("executeQuery refused");
        RuntimeException thrown = session.streamFailed(connection(), statement(false),
                resultSet(false), "SELECT 1", cause);
        assertSame(cause, thrown.getCause());
        assertEquals(List.of("rs.close", "ps.close", "release"), events);
    }

    // --- fakes ---------------------------------------------------------------

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    }

    private PreparedStatement statement(boolean failOnClose) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        events.add("ps.close");
                        if (failOnClose) {
                            throw new SQLException("ps.close refused");
                        }
                    }
                    return null;
                });
    }

    private ResultSet resultSet(boolean failOnClose) {
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        events.add("rs.close");
                        if (failOnClose) {
                            throw new SQLException("rs.close refused");
                        }
                    }
                    return null;
                });
    }

    /** A session that only records; conn() is never reached by these paths. */
    private final class RecordingSession implements LarkBatisSession {
        @Override
        public Connection conn() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(Connection c) {
            events.add("release");
        }

        @Override
        public RuntimeException translate(SQLException e, String sql) {
            return new LarkBatisException(e.getMessage(), sql, e);
        }
    }
}
