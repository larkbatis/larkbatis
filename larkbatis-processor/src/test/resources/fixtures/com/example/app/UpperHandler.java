package com.example.app;

import io.github.larkbatis.runtime.LarkBatisTypeHandler;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** A handler on a type the codec already knows — the reason kind alone is not enough. */
public class UpperHandler implements LarkBatisTypeHandler<String> {

    @Override
    public String read(ResultSet rs, int column) throws SQLException {
        String v = rs.getString(column);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    @Override
    public void write(PreparedStatement ps, int index, String value) throws SQLException {
        ps.setString(index, value == null ? null : value.toUpperCase(Locale.ROOT));
    }
}
