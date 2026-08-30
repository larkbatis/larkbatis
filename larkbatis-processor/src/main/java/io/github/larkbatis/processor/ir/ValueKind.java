package io.github.larkbatis.processor.ir;

/**
 * The closed whitelist of Java types LarkBatis knows how to move across
 * JDBC, and how generated code reads/writes each one. Everything outside this
 * list is a clear compile-time error — generating wrong code is far worse
 * than refusing to generate.
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
    /** No {@code rs.getChar}: JDBC moves a char as a one-character string. */
    PRIM_CHAR(null, null, "charValue", "setChar"),

    BOX_BOOLEAN(null, null, "booleanOrNull", "setBoolean"),
    BOX_BYTE(null, null, "byteOrNull", "setByte"),
    BOX_SHORT(null, null, "shortOrNull", "setShort"),
    BOX_INT(null, null, "intOrNull", "setInt"),
    BOX_LONG(null, null, "longOrNull", "setLong"),
    BOX_FLOAT(null, null, "floatOrNull", "setFloat"),
    BOX_DOUBLE(null, null, "doubleOrNull", "setDouble"),
    BOX_CHARACTER(null, null, "characterOrNull", "setCharacter"),

    STRING("getString", "setString", null, null),
    BIG_DECIMAL("getBigDecimal", "setBigDecimal", null, null),
    BIG_INTEGER(null, null, "bigInteger", "setBigInteger"),
    BYTES("getBytes", "setBytes", null, null),

    LOCAL_DATE(null, null, "localDate", "setLocalDate"),
    LOCAL_TIME(null, null, "localTime", "setLocalTime"),
    LOCAL_DATE_TIME(null, null, "localDateTime", "setLocalDateTime"),
    INSTANT(null, null, "instant", "setInstant"),
    OFFSET_DATE_TIME(null, null, "offsetDateTime", "setOffsetDateTime"),
    OFFSET_TIME(null, null, "offsetTime", "setOffsetTime"),
    ZONED_DATE_TIME(null, null, "zonedDateTime", "setZonedDateTime"),

    // --- the JDBC date types, moved without conversion ------------------------
    // Kept because a codebase of a certain age is full of them and the
    // migration is otherwise "edit every DTO first". Their accessors are
    // null-safe in both directions, like String and BigDecimal.
    SQL_DATE("getDate", "setDate", null, null),
    SQL_TIME("getTime", "setTime", null, null),
    SQL_TIMESTAMP("getTimestamp", "setTimestamp", null, null),
    /** Read as a Timestamp and narrowed, exactly as MyBatis's DateTypeHandler does. */
    UTIL_DATE(null, null, "utilDate", "setUtilDate"),

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
            case PRIM_BOOLEAN, PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG, PRIM_FLOAT,
                 PRIM_DOUBLE, PRIM_CHAR -> true;
            default -> false;
        };
    }

    /** Kinds whose value set is closed, i.e. acceptable inside {@code ${}}. */
    public boolean closedValueSet() {
        return switch (this) {
            case PRIM_BOOLEAN, PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG, ENUM -> true;
            default -> false;
        };
    }
}
