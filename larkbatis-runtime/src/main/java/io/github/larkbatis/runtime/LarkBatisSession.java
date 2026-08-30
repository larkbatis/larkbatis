package io.github.larkbatis.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What generated mapper implementations need from their environment:
 * a {@link Connection}, a way to give it back, and exception translation.
 *
 * <p>Generated code never puts the Connection in try-with-resources — under a
 * managed transaction {@code close()} is wrong; only {@link #release} knows
 * whether the connection may really be closed. The standalone
 * JDBC implementation is {@link JdbcLarkBatisSession}; the Spring one lives
 * in {@code larkbatis-spring} and goes through {@code DataSourceUtils}.
 */
public interface LarkBatisSession {

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

    /**
     * Wraps an open ResultSet as a stream of rows, handing it the Connection,
     * the statement and the ResultSet to release when the stream is closed.
     * Called by generated bodies of {@code Stream}-returning mapper methods.
     *
     * <p>Unlike every other generated shape, this one hands JDBC resources out
     * past the end of the method — so the caller owns them:
     * {@code try (Stream<User> rows = mapper.streamAll()) { ... }}. Outside a
     * transaction an unclosed stream holds a pooled Connection; inside one it
     * holds the statement and cursor until the transaction ends.
     */
    default <T> Stream<T> stream(Connection c, PreparedStatement ps, ResultSet rs,
            RowReader<T> reader, String sql) {
        return ResultSetStream.open(this, c, ps, rs, reader, sql);
    }

    /**
     * The failure path of a {@code Stream}-returning body: translate, then
     * give back whatever was already opened, with a cleanup failure suppressed
     * into the real one rather than replacing it. Returns the exception so the
     * generated body reads {@code throw s.streamFailed(...)} and the compiler
     * can see the method ends there.
     */
    default RuntimeException streamFailed(Connection c, PreparedStatement ps, ResultSet rs,
            String sql, SQLException cause) {
        return streamFailed(c, ps, rs, sql, translate(cause, sql));
    }

    /**
     * The same failure path for an exception that was never a
     * {@link SQLException} — an NPE from a getter chain binding
     * {@code #{user.name}} against a null bean is the ordinary case.
     *
     * <p>Every other generated shape returns its Connection from a
     * {@code finally}, so any exception releases it. A {@code Stream} body has
     * no {@code finally} by construction, which would leave an unchecked
     * throw holding the Connection, the statement and the cursor until the
     * pool timed out. Overloaded rather than widened to {@code Throwable}:
     * the generated body catches the two types separately, and overload
     * resolution — not an instanceof chain — picks the arm.
     */
    default RuntimeException streamFailed(Connection c, PreparedStatement ps, ResultSet rs,
            String sql, RuntimeException cause) {
        try {
            ResultSetStream.close(this, c, ps, rs, sql);
        } catch (RuntimeException cleanup) {
            cause.addSuppressed(cleanup);
        }
        return cause;
    }

    // --- manual escape hatch ------------------------------------
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

    /**
     * Like {@link #query}, but streaming — the caller closes the stream, which
     * closes the ResultSet and statement and releases the Connection.
     */
    default <T> Stream<T> queryStream(SqlFragment sql, StatementBinder binder,
            RowReader<T> reader) {
        String text = sql.text();
        Connection c = conn();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = c.prepareStatement(text);
            binder.bind(ps);
            rs = ps.executeQuery();
            return stream(c, ps, rs, reader, text);
        } catch (SQLException e) {
            // nothing else will run a finally for these: the happy path hands
            // them to the stream, so the failure path has to undo by hand
            throw streamFailed(c, ps, rs, text, e);
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
