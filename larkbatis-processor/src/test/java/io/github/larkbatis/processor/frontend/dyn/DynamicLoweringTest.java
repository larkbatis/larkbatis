package io.github.larkbatis.processor.frontend.dyn;

import io.github.larkbatis.processor.ir.DynamicModel;
import io.github.larkbatis.processor.ir.SqlPiece;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build-time fold of DynamicSqlSource + TrimSqlNode,
 * checked at the IR level with fake bind/test callbacks. The reference case
 * is the UserQuery search — its generated body is the landmark the emitter
 * reproduces.
 */
class DynamicLoweringTest {

    private static final java.util.List<DynNode.Foreach> PENDING = new java.util.ArrayList<>();

    private static final DynamicLowering.TokenLowerer TOKENS = new DynamicLowering.TokenLowerer() {
        @Override
        public SqlPiece hash(String expression) {
            return new SqlPiece.Bind(expression, "q.get" + expression + "()",
                    ValueKind.STRING, null, null);
        }

        @Override
        public SqlPiece dollar(String expression) {
            return new SqlPiece.Dollar(expression, SqlPiece.Dollar.DollarKind.FRAGMENT,
                    expression, List.of());
        }

        /** Fake resolution: the collection is a List<Long> named as written. */
        @Override
        public void enterForeach(DynNode.Foreach node) {
            PENDING.add(node);
        }

        @Override
        public DynamicLowering.ForeachPlan exitForeach() {
            DynNode.Foreach node = PENDING.remove(PENDING.size() - 1);
            return new DynamicLowering.ForeachPlan(node.collection(), node.collection(),
                    "java.util.List<java.lang.Long>",
                    node.collection() + ".size()", SqlPiece.Foreach.Iteration.COLLECTION,
                    node.item(), "java.lang.Long", List.of(), node.index(), false);
        }
    };

    /** Conditions compile to recognizable markers. */
    private static final DynamicLowering.TestCompiler TESTS = test -> "test(" + test + ")";

    private static DynamicLowering.Lowered lower(DynNode... nodes) {
        return DynamicLowering.lower(List.of(nodes), TOKENS, TESTS);
    }

    private static String textOf(DynamicModel.Segment segment) {
        StringBuilder sb = new StringBuilder();
        for (SqlPiece piece : segment.pieces()) {
            if (piece instanceof SqlPiece.Text t) {
                sb.append(t.sql());
            } else if (piece instanceof SqlPiece.Bind) {
                sb.append('?');
            } else if (piece instanceof SqlPiece.Alt a) {
                sb.append('<').append(a.whenTrue()).append('|').append(a.whenFalse()).append('>');
            }
        }
        return sb.toString();
    }

    // --- the landmark case ----------------------------------------------------

    @Test
    void whereWithTwoIfsFoldsLikeTheDesignDoc() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("\n  SELECT id, name, email FROM users\n  "),
                DynNode.Trim.where(List.of(
                        new DynNode.If("name != null",
                                List.of(new DynNode.Text("\n    AND name LIKE #{name}\n  "))),
                        new DynNode.If("minAge != null",
                                List.of(new DynNode.Text("\n    AND age >= #{minAge}\n  "))))),
                new DynNode.Text("\n  ORDER BY id\n"));

        assertTrue(lowered.dynamic());
        DynamicModel model = lowered.model();
        assertEquals(List.of(
                new DynamicModel.CondLocal("c0", "test(name != null)"),
                new DynamicModel.CondLocal("c1", "test(minAge != null)")),
                model.locals());

        List<DynamicModel.Segment> segments = model.segments();
        assertEquals(5, segments.size());

        assertNull(segments.get(0).guard());
        assertEquals("SELECT id, name, email FROM users", textOf(segments.get(0)));

        assertEquals("c0 | c1", segments.get(1).guard());
        assertEquals(" WHERE", textOf(segments.get(1)));

        assertEquals("c0", segments.get(2).guard());
        assertEquals(" name LIKE ?", textOf(segments.get(2)));

        // the second condition carries the folded AND: kept only when c0 fired
        assertEquals("c1", segments.get(3).guard());
        assertEquals("< AND age >= | age >= >?", textOf(segments.get(3)));
        SqlPiece.Alt alt = (SqlPiece.Alt) segments.get(3).pieces().get(0);
        assertEquals("c0", alt.condition());

        assertNull(segments.get(4).guard());
        assertEquals(" ORDER BY id", textOf(segments.get(4)));
    }

    @Test
    void setStripsTheTrailingCommaOfTheLastPresentFragment() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("UPDATE users"),
                DynNode.Trim.set(List.of(
                        new DynNode.If("name != null", List.of(new DynNode.Text(" name = #{name}, "))),
                        new DynNode.If("email != null", List.of(new DynNode.Text(" email = #{email}, "))))),
                new DynNode.Text(" WHERE id = #{id}"));

        List<DynamicModel.Segment> segments = lowered.model().segments();
        assertEquals("UPDATE users", textOf(segments.get(0)));
        assertEquals(" SET", textOf(segments.get(1)));
        assertEquals("c0 | c1", segments.get(1).guard());
        // first fragment keeps its comma only if the second one follows
        assertEquals(" name = ?<,|>", textOf(segments.get(2)));
        assertEquals("c1", ((SqlPiece.Alt) segments.get(2).pieces().get(2)).condition());
        // the last fragment's comma is always stripped
        assertEquals(" email = ?", textOf(segments.get(3)));
        assertEquals(" WHERE id = ?", textOf(segments.get(4)));
    }

    @Test
    void chooseIsFirstMatchWins() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("SELECT * FROM t WHERE 1 = 1"),
                new DynNode.Choose(List.of(
                        new DynNode.Choose.When("a != null", List.of(new DynNode.Text(" AND a = #{a}"))),
                        new DynNode.Choose.When("b != null", List.of(new DynNode.Text(" AND b = #{b}")))),
                        List.of(new DynNode.Text(" AND c = 1"))));

        DynamicModel model = lowered.model();
        assertEquals(List.of(
                new DynamicModel.CondLocal("c0", "test(a != null)"),
                new DynamicModel.CondLocal("c1", "!c0 && (test(b != null))"),
                new DynamicModel.CondLocal("c2", "!c0 && !c1")),
                model.locals());
        assertEquals(java.util.Arrays.asList(null, "c0", "c1", "c2"),
                model.segments().stream().map(DynamicModel.Segment::guard).toList());
    }

    @Test
    void whereAroundStaticContentFoldsToAStaticStatement() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("SELECT id FROM users"),
                DynNode.Trim.where(List.of(new DynNode.Text(" AND name = #{name} "))));

        assertFalse(lowered.dynamic());
        StringBuilder sql = new StringBuilder();
        for (SqlPiece piece : lowered.flatPieces()) {
            sql.append(piece instanceof SqlPiece.Text t ? t.sql() : "?");
        }
        assertEquals("SELECT id FROM users WHERE name = ?", sql.toString());
    }

    @Test
    void plainTextIsStaticAndNormalized() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("\n  SELECT id\n  FROM users\n  WHERE id = #{id}\n"));
        assertFalse(lowered.dynamic());
        assertEquals(new SqlPiece.Text("SELECT id FROM users WHERE id = "),
                lowered.flatPieces().get(0));
    }

    @Test
    void nestedIfShortCircuitsThroughTheOuterLocal() {
        DynamicLowering.Lowered lowered = lower(
                new DynNode.Text("SELECT 1"),
                new DynNode.If("list != null", List.of(
                        new DynNode.Text(" A"),
                        new DynNode.If("!list.isEmpty()", List.of(new DynNode.Text(" B"))))));

        assertEquals(List.of(
                new DynamicModel.CondLocal("c0", "test(list != null)"),
                new DynamicModel.CondLocal("c1", "c0 && (test(!list.isEmpty()))")),
                lowered.model().locals());
    }
}
