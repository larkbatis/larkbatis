package io.github.lightbatis.runtime;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Null-handling read/write helpers for the JDBC types whose natural accessor
 * is primitive-typed or needs a conversion. These are the inlined remains of
 * the MyBatis TypeHandler layer (design §02): the choice of which helper to
 * call was made at build time; only the value work happens here.
 */
public final class JdbcCodec {

    private JdbcCodec() {
    }

    // --- reads: wrapper types over primitive accessors -----------------------

    public static Boolean booleanOrNull(ResultSet rs, int column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }

    public static Byte byteOrNull(ResultSet rs, int column) throws SQLException {
        byte v = rs.getByte(column);
        return rs.wasNull() ? null : v;
    }

    public static Short shortOrNull(ResultSet rs, int column) throws SQLException {
        short v = rs.getShort(column);
        return rs.wasNull() ? null : v;
    }

    public static Integer intOrNull(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    public static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public static Float floatOrNull(ResultSet rs, int column) throws SQLException {
        float v = rs.getFloat(column);
        return rs.wasNull() ? null : v;
    }

    public static Double doubleOrNull(ResultSet rs, int column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    // --- reads: java.time conversions ----------------------------------------

    public static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    public static LocalDateTime localDateTime(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }

    public static LocalDate localDate(ResultSet rs, int column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    public static LocalTime localTime(ResultSet rs, int column) throws SQLException {
        Time t = rs.getTime(column);
        return t == null ? null : t.toLocalTime();
    }

    // --- enums: stored by name, chosen at build time (design §08 group 2) -----

    public static <E extends Enum<E>> E enumValue(ResultSet rs, int column, Class<E> type)
            throws SQLException {
        String name = rs.getString(column);
        return name == null ? null : Enum.valueOf(type, name);
    }

    public static void setEnum(PreparedStatement ps, int index, Enum<?> v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, v.name());
        }
    }

    // --- writes: wrapper types over primitive setters -------------------------

    public static void setBoolean(PreparedStatement ps, int index, Boolean v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.BOOLEAN);
        } else {
            ps.setBoolean(index, v);
        }
    }

    public static void setByte(PreparedStatement ps, int index, Byte v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.TINYINT);
        } else {
            ps.setByte(index, v);
        }
    }

    public static void setShort(PreparedStatement ps, int index, Short v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.SMALLINT);
        } else {
            ps.setShort(index, v);
        }
    }

    public static void setInt(PreparedStatement ps, int index, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, v);
        }
    }

    public static void setLong(PreparedStatement ps, int index, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, v);
        }
    }

    public static void setFloat(PreparedStatement ps, int index, Float v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, v);
        }
    }

    public static void setDouble(PreparedStatement ps, int index, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, v);
        }
    }

    // --- writes: java.time conversions ----------------------------------------

    public static void setInstant(PreparedStatement ps, int index, Instant v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.from(v));
        }
    }

    public static void setLocalDateTime(PreparedStatement ps, int index, LocalDateTime v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(v));
        }
    }

    public static void setLocalDate(PreparedStatement ps, int index, LocalDate v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(v));
        }
    }

    public static void setLocalTime(PreparedStatement ps, int index, LocalTime v) throws SQLException {
        if (v == null) {
            ps.setNull(index, Types.TIME);
        } else {
            ps.setTime(index, Time.valueOf(v));
        }
    }
}
