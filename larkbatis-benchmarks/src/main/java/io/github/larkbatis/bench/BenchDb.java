package io.github.larkbatis.bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * The database under every benchmark: H2, seeded once per JVM fork.
 *
 * <p>Both frameworks run against the same real JDBC driver, so the driver's
 * own cost is inside both numbers. That makes the measured gap a <em>lower
 * bound</em> on the mapper-layer gap rather than an isolated one — a stubbed
 * ResultSet would flatter LarkBatis, and this is a benchmark that has to
 * survive someone else re-running it.
 *
 * <p>Two transports: in-process ({@code mem}) and a loopback socket through
 * H2's TCP server ({@code tcp}). The second exists for the unmeasured "single
 * query latency with a real database at the other end" — a socket round trip
 * and a wire protocol, which is the part an in-process database hides. It is
 * still not a database on another host; the report says so.
 */
public final class BenchDb {

    /** Column count of the narrow shape; see {@link NarrowRow}. */
    public static final int NARROW_COLUMNS = 4;

    /** Column count of the wide shape. */
    public static final int WIDE_COLUMNS = 12;

    private BenchDb() {
    }

    public static JdbcDataSource memory(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    public static JdbcDataSource tcp(int port, String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:tcp://localhost:" + port + "/mem:" + name + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    /** Creates the three tables and fills them with {@code rows} rows each. */
    public static void seed(DataSource ds, int rows) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS narrow");
                st.execute("DROP TABLE IF EXISTS wide");
                st.execute("DROP TABLE IF EXISTS mega");
                st.execute("CREATE TABLE narrow ("
                        + "id BIGINT PRIMARY KEY, "
                        + "name VARCHAR(64) NOT NULL, "
                        + "email VARCHAR(64), "
                        + "created_at TIMESTAMP)");
                st.execute("CREATE TABLE wide ("
                        + "id BIGINT PRIMARY KEY, "
                        + "name VARCHAR(64) NOT NULL, "
                        + "email VARCHAR(64), "
                        + "created_at TIMESTAMP, "
                        + "code VARCHAR(16), "
                        + "status VARCHAR(16), "
                        + "quantity INT NOT NULL, "
                        + "ratio DOUBLE PRECISION NOT NULL, "
                        + "active BOOLEAN NOT NULL, "
                        + "score DOUBLE PRECISION, "
                        + "note VARCHAR(128), "
                        + "revision BIGINT)");
                st.execute("CREATE TABLE mega ("
                        + "id BIGINT PRIMARY KEY, "
                        + "name VARCHAR(64), "
                        + "code VARCHAR(16), "
                        + "amount DOUBLE PRECISION, "
                        + "flag BOOLEAN, "
                        + "created_at TIMESTAMP)");
            }
            fillNarrow(c, rows);
            fillWide(c, rows);
            fillMega(c);
        }
    }

    private static void fillNarrow(Connection c, int rows) throws SQLException {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO narrow (id, name, email, created_at) VALUES (?, ?, ?, ?)")) {
            for (int i = 1; i <= rows; i++) {
                ps.setLong(1, i);
                ps.setString(2, "user-" + i);
                // one in eight null, so the wasNull() path is exercised too
                ps.setString(3, i % 8 == 0 ? null : "user" + i + "@example.com");
                ps.setTimestamp(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void fillWide(Connection c, int rows) throws SQLException {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO wide"
                + " (id, name, email, created_at, code, status, quantity, ratio,"
                + " active, score, note, revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 1; i <= rows; i++) {
                ps.setLong(1, i);
                ps.setString(2, "user-" + i);
                ps.setString(3, i % 8 == 0 ? null : "user" + i + "@example.com");
                ps.setTimestamp(4, now);
                ps.setString(5, "C" + (i % 997));
                ps.setString(6, i % 3 == 0 ? "ACTIVE" : "IDLE");
                ps.setInt(7, i);
                ps.setDouble(8, i / 7.0);
                ps.setBoolean(9, i % 2 == 0);
                if (i % 5 == 0) {
                    ps.setNull(10, java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(10, i * 1.5);
                }
                ps.setString(11, "note for row " + i);
                ps.setLong(12, i * 3L);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One row per megamorphic bean type; the benchmark reads each by id. */
    private static void fillMega(Connection c) throws SQLException {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO mega (id, name, code, amount, flag, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 50; i++) {
                ps.setLong(1, i);
                ps.setString(2, "mega-" + i);
                ps.setString(3, "M" + i);
                ps.setDouble(4, i * 2.5);
                ps.setBoolean(5, i % 2 == 0);
                ps.setTimestamp(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
