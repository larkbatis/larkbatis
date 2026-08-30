package io.github.larkbatis.conformance;

import io.github.larkbatis.conformance.fixtures.ConformanceQueryMapper;
import io.github.larkbatis.conformance.fixtures.LarkBatisMappers;
import io.github.larkbatis.conformance.fixtures.User;
import io.github.larkbatis.conformance.fixtures.UserFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code <foreach>} differential suite: the same mapper XML run
 * through MyBatis's {@code ForEachSqlNode} and through the generated loops.
 * MyBatis routes each element through a generated
 * {@code __frch_item_N} binding in a HashMap and re-parses the assembled SQL
 * to find it again; LarkBatis writes two loops that walk the same elements
 * in the same order. This suite is what proves those arrive at the same SQL
 * and the same {@code setX} sequence.
 *
 * <p>The one intentional divergence — an empty collection throws instead of
 * silently contributing nothing — has no fixture here because there is no
 * oracle output to compare against; {@code ForeachMapperEndToEndTest} in
 * larkbatis-sample pins that behavior.
 */
class ForeachDifferentialTest {

    private static void assertIdentical(Consumer<ConformanceQueryMapper> call) {
        Recording oracle = DifferentialHarness.mybatis(ConformanceQueryMapper.class, call, true);
        Recording generated = DifferentialHarness.larkbatis(
                LarkBatisMappers::conformanceQueryMapper, call);
        assertEquals(oracle.dump(), generated.dump(),
                "generated <foreach> diverged from the MyBatis oracle");
    }

    /**
     * The same comparison with whitespace runs collapsed, used only where a
     * {@code <foreach>} sits inside a {@code <where>}/{@code <set>}/
     * {@code <trim>}. The two MyBatis versions disagree there, and this is
     * the version-drift check the {@code mybatis-internals} skill asks for:
     *
     * <ul>
     *   <li>3.5.19 — the executable oracle — concatenates a trim's
     *       contributions raw: {@code TrimSqlNode.FilteredDynamicContext
     *       .appendSql} is {@code sqlBuffer.append(sql)}, so the loop reads
     *       {@code id IN (?)}.</li>
     *   <li>3.6.0-SNAPSHOT — the reading oracle at {@code ../mybatis-3},
     *       which CLAUDE.md makes ground truth — inserts a space between
     *       them, giving {@code id IN ( ? )}, the string LarkBatis emits.</li>
     * </ul>
     *
     * <p>So this is not a generator bug to fix, and normalizing away the
     * difference everywhere would hide real ones: the binds and the
     * placeholder positions are still compared exactly, only whitespace runs
     * are collapsed, and only for these fixtures. Revisit when a 3.6 release
     * can replace the oracle.
     */
    private static void assertIdenticalModuloTrimWhitespace(
            Consumer<ConformanceQueryMapper> call) {
        Recording oracle = DifferentialHarness.mybatis(ConformanceQueryMapper.class, call, true);
        Recording generated = DifferentialHarness.larkbatis(
                LarkBatisMappers::conformanceQueryMapper, call);
        assertEquals(collapse(oracle.dump()), collapse(generated.dump()),
                "generated <foreach> diverged from the MyBatis oracle beyond the known"
                        + " 3.5.19/3.6 whitespace drift inside <trim>");
    }

    /**
     * Strips whitespace from the SQL lines only. The bind lines — which
     * {@code setX} ran, in which position, with which value — stay
     * byte-for-byte, so everything except the drift being tolerated is still
     * compared exactly.
     */
    private static String collapse(String dump) {
        StringBuilder out = new StringBuilder();
        for (String line : dump.split("\n", -1)) {
            int open = line.indexOf('|');
            int close = line.lastIndexOf('|');
            if (line.startsWith("prepare") && open >= 0 && close > open) {
                out.append(line, 0, open + 1)
                        .append(line.substring(open + 1, close).replaceAll("\\s+", ""))
                        .append(line.substring(close));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static UserFilter filter(Consumer<UserFilter> setup) {
        UserFilter filter = new UserFilter();
        setup.accept(filter);
        return filter;
    }

    // --- cardinality --------------------------------------------------------------

    @Test
    void oneElement() {
        assertIdentical(mapper -> mapper.findByIds(List.of(7L)));
    }

    @Test
    void twoElements() {
        assertIdentical(mapper -> mapper.findByIds(List.of(7L, 9L)));
    }

    @Test
    void manyElements() {
        assertIdentical(mapper -> mapper.findByIds(List.of(1L, 2L, 3L, 5L, 8L, 13L)));
    }

    // --- where the collection comes from --------------------------------------------

    @Test
    void collectionAsABeanProperty() {
        assertIdentical(mapper -> mapper.searchInIds(filter(f -> f.setIds(List.of(7L, 9L)))));
    }

    @Test
    void foreachInsideAnIfThatHolds() {
        assertIdenticalModuloTrimWhitespace(
                mapper -> mapper.searchOptionalIds(filter(f -> f.setIds(List.of(7L)))));
    }

    /** The guard is false: neither framework emits the loop or its binds. */
    @Test
    void foreachInsideAnIfThatDoesNot() {
        assertIdentical(mapper -> mapper.searchOptionalIds(filter(f -> f.setName("A%"))));
    }

    @Test
    void foreachInsideAnIfAlongsideAnotherCondition() {
        assertIdenticalModuloTrimWhitespace(mapper -> mapper.searchOptionalIds(filter(f -> {
            f.setName("A%");
            f.setIds(List.of(7L, 9L));
        })));
    }

    // --- item, index, and two loops over one collection -------------------------------

    @Test
    void indexBoundAlongsideItem() {
        assertIdentical(mapper -> mapper.findByIdsOrdered(List.of(9L, 7L, 13L)));
    }

    /** Map iteration binds the key to index and the value to item (issue #709). */
    @Test
    void mapEntryIteration() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("Ada", "ada@example.com");
        filters.put("Grace", "grace@example.com");
        assertIdentical(mapper -> mapper.findByColumnValues(filters));
    }

    @Test
    void singleEntryMap() {
        assertIdentical(mapper -> mapper.findByColumnValues(Map.of("Ada", "ada@example.com")));
    }

    /**
     * Nested loops. MyBatis rebinds {@code __frch_id_N} per element of the
     * inner loop with a globally unique number; the generated code just nests
     * two ordinary for loops.
     */
    @Test
    void nestedForeach() {
        assertIdentical(mapper -> mapper.findByIdGroups(
                List.of(List.of(1L, 2L), List.of(7L))));
    }

    @Test
    void nestedForeachWithOneGroup() {
        assertIdentical(mapper -> mapper.findByIdGroups(List.of(List.of(1L, 2L))));
    }

    /** The loop lives in a &lt;when&gt; branch that wins. */
    @Test
    void foreachInsideAChosenWhen() {
        assertIdentical(mapper -> mapper.chooseWithForeach(filter(f -> f.setIds(List.of(7L, 9L)))));
    }

    /** …and one that does not: no loop, no binds, on either side. */
    @Test
    void foreachInsideAWhenThatLoses() {
        assertIdentical(mapper -> mapper.chooseWithForeach(filter(f -> f.setName("A%"))));
    }

    // --- the loop at a trim edge / at the start of a statement --------------------------

    /**
     * The loop's {@code open} carries the token the {@code <where>} strips.
     * TrimSqlNode strips against the assembled body, so a loop at the edge is
     * not exempt — {@code WHERE AND id IN (...)} would be a syntax error.
     */
    @Test
    void foreachOpenCarriesTheStrippedToken() {
        assertIdenticalModuloTrimWhitespace(mapper -> mapper.foreachOpensTheWhere(List.of(7L)));
    }

    /** The statement starts with the loop: only its first unit is space-less. */
    @Test
    void statementOpeningWithABareForeach() {
        assertIdentical(mapper -> mapper.unionOfIds(List.of(7L, 9L)));
    }

    @Test
    void statementOpeningWithABareForeachSingleElement() {
        assertIdentical(mapper -> mapper.unionOfIds(List.of(7L)));
    }

    // --- whitespace edges ---------------------------------------------------------------

    /** The loop is the whole content of a &lt;where&gt;, so the trim sees only it. */
    @Test
    void foreachAsTheWholeWhereClause() {
        assertIdenticalModuloTrimWhitespace(
                mapper -> mapper.searchWhereForeachOnly(List.of(7L, 9L)));
    }

    /** Two loops with nothing between them: each carries its own joiner space. */
    @Test
    void adjacentLoops() {
        assertIdentical(mapper -> mapper.adjacentForeach(List.of(7L, 9L), List.of("Ada")));
    }

    /** A separator that is only whitespace — the degenerate case of the fold. */
    @Test
    void whitespaceOnlySeparator() {
        assertIdentical(mapper -> mapper.spaceSeparator(List.of(7L, 9L)));
    }

    // --- writes -----------------------------------------------------------------------

    @Test
    void multiRowInsert() {
        assertIdentical(mapper -> mapper.insertAll(List.of(
                user("Barbara", "barbara@example.com"),
                user("Katherine", "katherine@example.com"))));
    }

    @Test
    void singleRowInsertThroughTheSameLoop() {
        assertIdentical(mapper -> mapper.insertAll(List.of(user("Ada", "ada@example.com"))));
    }

    @Test
    void foreachNextToAnOrdinaryBind() {
        assertIdentical(mapper -> mapper.deleteByIds(List.of(1L, 2L, 3L), "Grace"));
    }

    private static User user(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return user;
    }
}
