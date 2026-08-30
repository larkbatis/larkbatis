package com.example.app;

import io.github.larkbatis.runtime.LarkBatisTypeHandler;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** A handler that wants construction arguments — and so is not a shared singleton. */
public class NeedsArgHandler implements LarkBatisTypeHandler<Money> {

    private final long scale;

    public NeedsArgHandler(long scale) {
        this.scale = scale;
    }

    @Override
    public Money read(ResultSet rs, int column) throws SQLException {
        return new Money(rs.getLong(column) * scale);
    }

    @Override
    public void write(PreparedStatement ps, int index, Money value) throws SQLException {
        ps.setLong(index, value.cents() / scale);
    }
}
