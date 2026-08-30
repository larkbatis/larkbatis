package io.github.larkbatis.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Moves one value between JDBC and a Java type the built-in codec does not
 * cover. This is the whole of what survives the MyBatis {@code TypeHandler}
 * layer: a pair of methods, called directly.
 *
 * <p>Which implementation runs for a column or a parameter is decided at build
 * time from an explicit {@code @Handler} or a mapper XML {@code typeHandler}
 * attribute, and the generated code names the class. There is no registry, no
 * {@code (Type, JdbcType)} lookup and no discovery scan, so nothing here is
 * resolved from a value at run time.
 *
 * <p>Implementations must be <strong>stateless and thread-safe</strong>, and
 * must have a public no-argument constructor: generated code holds one instance
 * in a {@code static final} field shared by every caller. The processor checks
 * both and refuses to generate otherwise, which is also why a handler cannot
 * take a dependency — an implementation that needs one belongs behind the
 * escape hatch, where the binder and the reader are the caller's own.
 *
 * <p>A handler owns {@code null} in both directions. {@link #read} is called
 * without {@code wasNull} having been consulted, and {@link #write} is called
 * with whatever the parameter holds, {@code null} included; that is why there
 * is no {@code jdbcType} anywhere in this interface. A handler that maps
 * {@code null} to a sentinel and one that maps it to {@code setNull} are both
 * legitimate, and only the handler knows which it is.
 *
 * @param <J> the Java type this handler produces and accepts
 */
public interface LarkBatisTypeHandler<J> {

    /**
     * Reads the value at {@code column} of the current row. The index is
     * resolved by the caller — literal when the generator parsed the select
     * list, from {@code ResultSetMetaData} once on the first row when it could
     * not — so an implementation never needs a column name.
     *
     * @param rs     positioned on a row
     * @param column 1-based column index
     * @return the value, possibly {@code null}
     * @throws SQLException from the driver, or to reject an unreadable value
     */
    J read(ResultSet rs, int column) throws SQLException;

    /**
     * Binds {@code value} at {@code index}. Called for {@code null} too, so an
     * implementation that needs {@code setNull} must call it itself.
     *
     * @param ps    the statement being bound
     * @param index 1-based parameter index
     * @param value the value to bind, possibly {@code null}
     * @throws SQLException from the driver, or to reject an unbindable value
     */
    void write(PreparedStatement ps, int index, J value) throws SQLException;
}
