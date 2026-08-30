package io.github.larkbatis.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads one row of a {@link ResultSet} into a value. Implementations are
 * generated at build time; the manual escape hatch reuses those
 * generated readers so the result type stays compile-checked.
 */
@FunctionalInterface
public interface RowReader<T> {

    T read(ResultSet rs) throws SQLException;
}
