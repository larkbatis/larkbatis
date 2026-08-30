package io.github.larkbatis.conformance;

import io.github.larkbatis.conformance.fixtures.ConformanceSquadMapper;
import io.github.larkbatis.conformance.fixtures.ConformanceSquadMapper$$Impl;
import io.github.larkbatis.conformance.fixtures.Squad;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The nested result map against the oracle. Unlike every other suite here,
 * this one compares the *result*, not the SQL: both frameworks emit the same
 * SELECT for a {@code <collection>}, and the whole question is how the join's
 * rows collapse back into objects — the LEFT JOIN miss, the parent that spans
 * several rows, the child order.
 *
 * <p>MyBatis builds a {@code CacheKey} per row and looks the parent up in a
 * map; LarkBatis compares typed key locals in a loop and requires the
 * ResultSet ordered by the parent key. These tests are where that trade is
 * either equivalent or not.
 */
class ResultMapDifferentialTest {

    private DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:conf_rmap_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE captain (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            st.execute("CREATE TABLE squad (id BIGINT PRIMARY KEY, name VARCHAR(50),"
                    + " captain_id BIGINT)");
            st.execute("CREATE TABLE player (id BIGINT PRIMARY KEY, squad_id BIGINT,"
                    + " name VARCHAR(50), shirt INT)");
            st.execute("INSERT INTO captain VALUES (10, 'Beckenbauer'), (11, 'Maldini')");
            // squad 3 has neither captain nor players: two LEFT JOIN misses
            st.execute("INSERT INTO squad VALUES (1, 'Alpha', 10), (2, 'Beta', 11),"
                    + " (3, 'Gamma', NULL)");
            st.execute("INSERT INTO player VALUES (100, 1, 'Ada', 7), (101, 1, 'Alan', 3),"
                    + " (102, 1, 'Grace', 9), (200, 2, 'Edsger', 1)");
        }
        this.dataSource = h2;
    }

    @Test
    void collectionMatchesTheOracle() {
        assertSameGraph(ConformanceSquadMapper::allWithPlayers);
    }

    @Test
    void associationMatchesTheOracle() {
        assertSameGraph(ConformanceSquadMapper::allWithCaptain);
    }

    @Test
    void singleParentShapeMatchesTheOracle() {
        assertSameGraph(mapper -> List.of(mapper.oneWithPlayers(1)));
        assertSameGraph(mapper -> List.of(mapper.oneWithPlayers(3)));
    }

    private void assertSameGraph(Function<ConformanceSquadMapper, List<Squad>> call) {
        String oracle = describe(DifferentialHarness.mybatisResult(
                dataSource, ConformanceSquadMapper.class, call));
        String actual = describe(DifferentialHarness.larkbatisResult(
                dataSource, ConformanceSquadMapper$$Impl::new, call));
        assertEquals(oracle, actual);
    }

    /**
     * The comparison is a rendering rather than {@code equals}: the fixtures
     * have no {@code equals}, and a text form makes a mismatch readable
     * instead of "expected Squad@1a2b, was Squad@3c4d".
     */
    private static String describe(List<Squad> squads) {
        return squads.stream().map(squad -> {
            StringBuilder sb = new StringBuilder();
            sb.append("squad ").append(squad.getId()).append(' ').append(squad.getName());
            sb.append(" captain=").append(squad.getCaptain() == null ? "null"
                    : squad.getCaptain().getId() + ":" + squad.getCaptain().getName());
            sb.append(" players=").append(squad.getPlayers() == null ? "null"
                    : squad.getPlayers().stream()
                            .map(p -> p.getId() + ":" + p.getName() + ":" + p.getShirt())
                            .collect(Collectors.joining(",", "[", "]")));
            return sb.toString();
        }).collect(Collectors.joining("\n"));
    }
}
