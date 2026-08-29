package io.github.lightbatis.runtime.handwritten;

import io.github.lightbatis.runtime.JdbcCodec;
import io.github.lightbatis.runtime.LightBatisSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * HAND-WRITTEN emitter spec (M1 task 1) — this file is the target the mapper
 * emitter must reproduce; keep it in the repo as the landmark the golden
 * files are compared against (build plan §05).
 *
 * <p>Shape rules it encodes:
 * <ul>
 *   <li>SQL is a private static final constant; {@code #{}} already lowered
 *       to positional {@code ?} at build time (design §04).</li>
 *   <li>The Connection is NOT in try-with-resources: borrowed via
 *       {@code s.conn()}, returned via {@code s.release(c)} in a finally —
 *       under a managed transaction close() would be wrong (design §10).</li>
 *   <li>Generated keys pass explicit column names to prepareStatement —
 *       Oracle returns ROWID and PostgreSQL returns all columns under plain
 *       RETURN_GENERATED_KEYS (design §07).</li>
 *   <li>Every SQLException funnels through {@code s.translate(e, sql)}.</li>
 * </ul>
 */
public final class UserMapper$$Impl implements UserMapper {

    private static final String SQL_findById =
            "SELECT id, name, email, created_at FROM users WHERE id = ?";

    private static final String SQL_insert =
            "INSERT INTO users (name, email, created_at) VALUES (?, ?, ?)";

    private static final String[] KEYS_insert = { "id" };

    private final LightBatisSession s;

    public UserMapper$$Impl(LightBatisSession s) {
        this.s = s;
    }

    @Override
    public User findById(long id) {
        Connection c = s.conn();
        try (PreparedStatement ps = c.prepareStatement(SQL_findById)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UserRow.read(rs) : null;
            }
        } catch (SQLException e) {
            throw s.translate(e, SQL_findById);
        } finally {
            s.release(c);
        }
    }

    @Override
    public int insert(User u) {
        Connection c = s.conn();
        try (PreparedStatement ps = c.prepareStatement(SQL_insert, KEYS_insert)) {
            ps.setString(1, u.getName());
            ps.setString(2, u.getEmail());
            JdbcCodec.setInstant(ps, 3, u.getCreatedAt());
            int n = ps.executeUpdate();
            try (ResultSet gk = ps.getGeneratedKeys()) {
                if (gk.next()) {
                    u.setId(gk.getLong(1));
                }
            }
            return n;
        } catch (SQLException e) {
            throw s.translate(e, SQL_insert);
        } finally {
            s.release(c);
        }
    }
}
