package io.github.lightbatis.processor.ir;

/**
 * The closed whitelist of Java types LightBatis knows how to move across
 * JDBC, and how generated code reads/writes each one. Everything outside this
 * list is a clear compile-time error — generating wrong code is far worse
 * than refusing to generate (build plan §08, risk 3).
 *
 * <p>Each constant carries the emit strategy: either a direct
 * {@code ResultSet}/{@code PreparedStatement} accessor, or a
 * {@code JdbcCodec} helper when null handling or a conversion is involved.
 */
public enum ValueKind {

    PRIM_BOOLEAN("getBoolean", "setBoolean", null, null),
    PRIM_BYTE("getByte", "setByte", null, null),
    PRIM_SHORT("getShort", "setShort", null, null),
    PRIM_INT("getInt", "setInt", null, null),
    PRIM_LONG("getLong", "setLong", null, null),
    PRIM_FLOAT("getFloat", "setFloat", null, null),
    PRIM_DOUBLE("getDouble", "setDouble", null, null),

    BOX_BOOLEAN(null, null, "booleanOrNull", "setBoolean"),
    BOX_BYTE(null, null, "byteOrNull", "setByte"),
    BOX_SHORT(null, null, "shortOrNull", "setShort"),
    BOX_INT(null, null, "intOrNull", "setInt"),
    BOX_LONG(null, null, "longOrNull", "setLong"),
    BOX_FLOAT(null, null, "floatOrNull", "setFloat"),
    BOX_DOUBLE(null, null, "doubleOrNull", "setDouble"),

    STRING("getString", "setString", null, null),
    BIG_DECIMAL("getBigDecimal", "setBigDecimal", null, null),
    BYTES("getBytes", "setBytes", null, null),

    LOCAL_DATE(null, null, "localDate", "setLocalDate"),
    LOCAL_TIME(null, null, "localTime", "setLocalTime"),
    LOCAL_DATE_TIME(null, null, "localDateTime", "setLocalDateTime"),
    INSTANT(null, null, "instant", "setInstant"),

    /** Stored by name; read via {@code JdbcCodec.enumValue(rs, i, X.class)}. */
    ENUM(null, null, "enumValue", "setEnum");

    /** Direct {@link java.sql.ResultSet} getter name, or null when a codec helper is needed. */
    private final String resultSetGetter;
    /** Direct {@link java.sql.PreparedStatement} setter name, or null when a codec helper is needed. */
    private final String statementSetter;
    /** {@code JdbcCodec} read helper name, or null when the direct getter suffices. */
    private final String codecReader;
    /** {@code JdbcCodec} write helper name, or null when the direct setter suffices. */
    private final String codecWriter;

    ValueKind(String resultSetGetter, String statementSetter, String codecReader, String codecWriter) {
        this.resultSetGetter = resultSetGetter;
        this.statementSetter = statementSetter;
        this.codecReader = codecReader;
        this.codecWriter = codecWriter;
    }

    public String resultSetGetter() {
        return resultSetGetter;
    }

    public String statementSetter() {
        return statementSetter;
    }

    public String codecReader() {
        return codecReader;
    }

    public String codecWriter() {
        return codecWriter;
    }

    public boolean primitive() {
        return switch (this) {
            case PRIM_BOOLEAN, PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG, PRIM_FLOAT, PRIM_DOUBLE -> true;
            default -> false;
        };
    }

    /** Kinds whose value set is closed, i.e. acceptable inside {@code ${}} (design §08). */
    public boolean closedValueSet() {
        return switch (this) {
            case PRIM_BOOLEAN, PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG, ENUM -> true;
            default -> false;
        };
    }
}
