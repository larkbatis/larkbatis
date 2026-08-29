package io.github.lightbatis.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads one row of a {@link ResultSet} into a value. Implementations are
 * generated at build time (design §04); the manual escape hatch reuses those
 * generated readers so the result type stays compile-checked (design §09).
 */
@FunctionalInterface
public interface RowReader<T> {

    T read(ResultSet rs) throws SQLException;
}
