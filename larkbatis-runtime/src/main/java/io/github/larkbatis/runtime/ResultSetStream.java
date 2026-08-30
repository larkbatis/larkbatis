package io.github.larkbatis.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The plumbing behind a {@code Stream}-returning mapper method: rows pulled
 * one at a time from a still-open {@link ResultSet}, and a close action that
 * gives back everything the mapper method borrowed.
 *
 * <p>This is the one generated shape where the JDBC resources outlive the
 * method that opened them, which is exactly why the stream must be closed —
 * {@code try (Stream<User> rows = mapper.streamAll())}. A stream that is
 * never closed holds a Connection for as long as it is reachable.
 *
 * <p>Package-private on purpose: generated code reaches it through
 * {@link LarkBatisSession#stream}, so the lifecycle is documented in one
 * place instead of being re-derived per emitter.
 */
final class ResultSetStream {

    private ResultSetStream() {
    }

    static <T> Stream<T> open(LarkBatisSession session, Connection c, PreparedStatement ps,
            ResultSet rs, RowReader<T> reader, String sql) {
        // ORDERED only. NONNULL would be a lie a scalar stream tells: a
        // Stream<String> over a nullable column yields null for a NULL row,
        // and a downstream operator is entitled to believe the characteristic.
        Spliterator<T> rows = new Spliterators.AbstractSpliterator<T>(
                Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                try {
                    if (!rs.next()) {
                        return false;
                    }
                    action.accept(reader.read(rs));
                    return true;
                } catch (SQLException e) {
                    throw session.translate(e, sql);
                }
            }
        };
        // sequential: the ResultSet is a cursor, and splitting it would mean
        // reading ahead into memory — which is the thing a Stream return is
        // chosen to avoid
        return StreamSupport.stream(rows, false)
                .onClose(() -> close(session, c, ps, rs, sql));
    }

    /**
     * Release in the reverse order of acquisition, and let the first failure
     * win. A ResultSet that refuses to close must not keep the Connection out
     * of the pool.
     */
    static void close(LarkBatisSession session, Connection c, PreparedStatement ps,
            ResultSet rs, String sql) {
        RuntimeException primary = null;
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            primary = session.translate(e, sql);
        }
        try {
            if (ps != null) {
                ps.close();
            }
        } catch (SQLException e) {
            if (primary == null) {
                primary = session.translate(e, sql);
            } else {
                primary.addSuppressed(e);
            }
        }
        try {
            session.release(c);
        } catch (RuntimeException e) {
            if (primary == null) {
                primary = e;
            } else {
                primary.addSuppressed(e);
            }
        }
        if (primary != null) {
            throw primary;
        }
    }
}
