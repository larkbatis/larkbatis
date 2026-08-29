package io.github.lightbatis.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What generated mapper implementations need from their environment:
 * a {@link Connection}, a way to give it back, and exception translation.
 *
 * <p>Generated code never puts the Connection in try-with-resources — under a
 * managed transaction {@code close()} is wrong; only {@link #release} knows
 * whether the connection may really be closed (design §10). The standalone
 * JDBC implementation is {@link JdbcLightBatisSession}; the Spring one lives
 * in {@code lightbatis-spring} and goes through {@code DataSourceUtils}.
 */
public interface LightBatisSession {

    /**
     * Borrow a connection. Inside an active transaction scope this returns
     * the transaction's connection; otherwise a fresh auto-commit one.
     */
    Connection conn();

    /**
     * Return a connection borrowed with {@link #conn()}. A no-op when the
     * connection belongs to an active transaction; closes it otherwise.
     */
    void release(Connection c);

    /** Translate a checked {@link SQLException} into the unchecked tree. */
    RuntimeException translate(SQLException e, String sql);

    // --- manual escape hatch (design §09) ------------------------------------
    // SQL assembled by the call site still enters through SqlFragment — the
    // same audited gate as ${} — and rows are read by a generated RowReader,
    // so no reflection is needed and the result type stays compile-checked.

    /** Run a hand-assembled query, reading each row with a generated reader. */
    default <T> List<T> query(SqlFragment sql, StatementBinder binder, RowReader<T> reader) {
        String text = sql.text();
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(text)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(reader.read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw translate(e, text);
        } finally {
            release(c);
        }
    }

    /** Like {@link #query}, but expects zero rows or one. */
    default <T> T queryOne(SqlFragment sql, StatementBinder binder, RowReader<T> reader) {
        String text = sql.text();
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(text)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? reader.read(rs) : null;
            }
        } catch (SQLException e) {
            throw translate(e, text);
        } finally {
            release(c);
        }
    }

    /** Run a hand-assembled INSERT/UPDATE/DELETE; returns the update count. */
    default int update(SqlFragment sql, StatementBinder binder) {
        String text = sql.text();
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(text)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw translate(e, text);
        } finally {
            release(c);
        }
    }
}
