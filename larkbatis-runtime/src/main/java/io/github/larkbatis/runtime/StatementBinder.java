package io.github.larkbatis.runtime;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds parameters onto a {@link PreparedStatement}. Used by the manual
 * escape hatch, where the call site assembled the SQL itself and
 * therefore knows the parameter positions.
 */
@FunctionalInterface
public interface StatementBinder {

    void bind(PreparedStatement ps) throws SQLException;
}
