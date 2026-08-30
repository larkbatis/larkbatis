package io.github.larkbatis.conformance.fixtures;

import io.github.larkbatis.runtime.LarkBatisTypeHandler;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * One class, both contracts. The mapper XML names it once and each framework
 * picks up the half it understands, so the two sides cannot be reading through
 * different conversion logic — which is the only way this comparison means
 * anything.
 *
 * <p>The shapes are worth reading side by side. MyBatis needs four methods and
 * a {@code JdbcType} it mostly ignores, because the handler is found in a
 * registry at run time and has to serve every call shape the registry might
 * route to it. LarkBatis needs two, because the call site is chosen at build
 * time and there is only ever one.
 */
public class AmountHandler implements LarkBatisTypeHandler<Amount>, TypeHandler<Amount> {

    // --- LarkBatis ---------------------------------------------------------

    @Override
    public Amount read(ResultSet rs, int column) throws SQLException {
        long cents = rs.getLong(column);
        return rs.wasNull() ? null : new Amount(cents);
    }

    @Override
    public void write(PreparedStatement ps, int index, Amount value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value.cents());
        }
    }

    // --- MyBatis ------------------------------------------------------------

    @Override
    public void setParameter(PreparedStatement ps, int i, Amount parameter, JdbcType jdbcType)
            throws SQLException {
        write(ps, i, parameter);
    }

    @Override
    public Amount getResult(ResultSet rs, String columnName) throws SQLException {
        long cents = rs.getLong(columnName);
        return rs.wasNull() ? null : new Amount(cents);
    }

    @Override
    public Amount getResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs, columnIndex);
    }

    @Override
    public Amount getResult(CallableStatement cs, int columnIndex) throws SQLException {
        long cents = cs.getLong(columnIndex);
        return cs.wasNull() ? null : new Amount(cents);
    }
}
