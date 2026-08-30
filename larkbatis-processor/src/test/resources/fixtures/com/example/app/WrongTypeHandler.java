package com.example.app;

import io.github.larkbatis.runtime.LarkBatisTypeHandler;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Handles String; pointed at a Money property on purpose. */
public class WrongTypeHandler implements LarkBatisTypeHandler<String> {

    @Override
    public String read(ResultSet rs, int column) throws SQLException {
        return rs.getString(column);
    }

    @Override
    public void write(PreparedStatement ps, int index, String value) throws SQLException {
        ps.setString(index, value);
    }
}
