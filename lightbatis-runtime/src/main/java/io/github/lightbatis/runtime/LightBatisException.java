package io.github.lightbatis.runtime;

/**
 * Root of the LightBatis exception tree. Wraps a {@link java.sql.SQLException}
 * together with the SQL text (or a pseudo-statement id such as
 * {@code "tx:commit"}) that was executing when it happened.
 */
public class LightBatisException extends RuntimeException {

    private final String sql;

    public LightBatisException(String message, String sql) {
        super(message);
        this.sql = sql;
    }

    public LightBatisException(String message, String sql, Throwable cause) {
        super(message, cause);
        this.sql = sql;
    }

    /** The SQL text (or pseudo-statement id) associated with this failure. */
    public String sql() {
        return sql;
    }
}
