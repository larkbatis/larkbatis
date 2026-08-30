package io.github.larkbatis.runtime.handwritten;

import io.github.larkbatis.runtime.JdbcCodec;
import io.github.larkbatis.runtime.RowReader;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * HAND-WRITTEN emitter spec — this file is the target the row
 * reader emitter must reproduce; keep it in the repo as the landmark the
 * golden files are compared against.
 *
 * <p>Positional indexes are valid because the generator read and controls the
 * select list. One reader exists per result class, and the
 * {@link #READER} constant is what the manual escape hatch reuses.
 */
public final class UserRow {

    public static final RowReader<User> READER = UserRow::read;

    private UserRow() {
    }

    public static User read(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getLong(1));
        u.setName(rs.getString(2));
        u.setEmail(rs.getString(3));
        u.setCreatedAt(JdbcCodec.instant(rs, 4));
        return u;
    }
}
