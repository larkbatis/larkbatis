package com.example.lbsample;

import io.github.larkbatis.runtime.JdbcLarkBatisSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-level join against real JDBC. The interesting cases are the ones
 * that only exist because the join is real: a parent with no children (the
 * LEFT JOIN still produces a row, all child columns NULL), two parents in one
 * ResultSet, and a parent whose children arrive in a different order than its
 * key.
 */
class ResultMapEndToEndTest {

    private TeamMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rmap_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE coach (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            st.execute("CREATE TABLE team (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
                    + "coach_id BIGINT)");
            st.execute("CREATE TABLE member (id BIGINT PRIMARY KEY, team_id BIGINT NOT NULL, "
                    + "name VARCHAR(50) NOT NULL, jersey INT NOT NULL)");
            st.execute("INSERT INTO coach VALUES (10, 'Herrera'), (11, 'Cruyff')");
            // team 3 has no coach and no members: both LEFT JOIN misses
            st.execute("INSERT INTO team VALUES (1, 'Reds', 10), (2, 'Blues', 11), (3, 'Greens', NULL)");
            st.execute("INSERT INTO member VALUES "
                    + "(100, 1, 'Ada', 7), (101, 1, 'Alan', 3), (102, 1, 'Grace', 9), "
                    + "(200, 2, 'Edsger', 1)");
        }
        mapper = LarkBatisMappers.teamMapper(new JdbcLarkBatisSession(dataSource));
    }

    @Test
    void collapsesTheJoinBackIntoOneParentPerKey() {
        List<Team> teams = mapper.findAllWithMembers();
        assertEquals(3, teams.size(), "the join's rows leaked into the result");
        assertEquals(List.of("Reds", "Blues", "Greens"),
                teams.stream().map(Team::getName).toList());
        assertEquals(3, teams.get(0).getMembers().size());
        assertEquals(1, teams.get(1).getMembers().size());
    }

    @Test
    void childrenKeepTheQuerysOrderNotTheParentKeys() {
        // ORDER BY t.id, m.jersey — the grouping loop must not reorder them
        List<Member> members = mapper.findAllWithMembers().get(0).getMembers();
        assertEquals(List.of(3, 7, 9), members.stream().map(Member::getJersey).toList());
        assertEquals(List.of("Alan", "Ada", "Grace"), members.stream().map(Member::getName).toList());
    }

    @Test
    void aParentWithNoChildrenGetsAnEmptyListNotANullOne() {
        Team greens = mapper.findAllWithMembers().get(2);
        assertNotNull(greens.getMembers(), "a LEFT JOIN miss left the collection null");
        assertTrue(greens.getMembers().isEmpty(),
                "the all-NULL row of a LEFT JOIN miss was read as a member");
    }

    @Test
    void singleParentShapeStillCollectsEveryChildRow() {
        Team reds = mapper.findWithMembers(1);
        assertNotNull(reds);
        assertEquals("Reds", reds.getName());
        assertEquals(3, reds.getMembers().size());

        Team greens = mapper.findWithMembers(3);
        assertNotNull(greens);
        assertTrue(greens.getMembers().isEmpty());

        assertNull(mapper.findWithMembers(999));
    }

    @Test
    void associationSetsOneChildAndLeavesAMissNull() {
        List<Team> teams = mapper.findAllWithCoach();
        assertEquals(3, teams.size());
        assertEquals("Herrera", teams.get(0).getCoach().getName());
        assertEquals("Cruyff", teams.get(1).getCoach().getName());
        assertNull(teams.get(2).getCoach(), "a LEFT JOIN miss produced a Coach object");
    }

    @Test
    void theNameBasedNestedPathAgreesWithThePositionalOne() {
        assertEquals(describe(mapper.findAllWithMembers()),
                describe(mapper.findAllWithMembersByName()));
    }

    private static String describe(List<Team> teams) {
        return teams.stream()
                .map(t -> t.getId() + ":" + t.getName() + ":" + t.getMembers().stream()
                        .map(m -> m.getId() + "/" + m.getName() + "/" + m.getJersey())
                        .toList())
                .toList()
                .toString();
    }

    @Test
    void aFlatResultMapOverSelectStarResolvesItsColumnsFromMetadata() {
        List<Team> teams = mapper.findAllFlat();
        assertEquals(List.of("Reds", "Blues", "Greens"),
                teams.stream().map(Team::getName).toList());
        assertEquals(List.of(1L, 2L, 3L), teams.stream().map(Team::getId).toList());
        // the map does not mention members or coach, so they stay untouched
        assertNull(teams.get(0).getMembers());
        assertNull(teams.get(0).getCoach());
    }
}
