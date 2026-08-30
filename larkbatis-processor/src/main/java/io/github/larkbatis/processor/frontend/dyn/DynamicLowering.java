package io.github.larkbatis.processor.frontend.dyn;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.SqlTokenizer;
import io.github.larkbatis.processor.ir.DynamicModel;
import io.github.larkbatis.processor.ir.SqlPiece;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lowers a {@link DynNode} tree to condition locals plus guarded SQL segments
 * — the compile-time replacement for DynamicSqlSource +
 * TrimSqlNode. Each {@code <if>}/{@code <when>} becomes one boolean local,
 * computed once and reused for SQL assembly and parameter binding alike;
 * {@code <trim>} prefix/suffix stripping is folded into {@link SqlPiece.Alt}
 * pieces whose condition is "did anything before/after me contribute".
 *
 * <p>Whitespace model: MyBatis joins SqlNode contributions with a single
 * space ({@code DynamicContext}'s {@code StringJoiner(" ")}) while keeping
 * the raw XML whitespace inside each text node. LarkBatis normalizes
 * instead: runs of whitespace inside a text piece collapse to one space,
 * segment edges are trimmed, and one space is baked in front of every
 * segment after the first. The SQL differs from MyBatis only in whitespace —
 * the differential harness compares whitespace-normalized, since SQL is
 * whitespace-insensitive.
 */
public final class DynamicLowering {

    /** Frontend callback lowering {@code #{}} / {@code ${}} to typed pieces. */
    public interface TokenLowerer {
        SqlPiece hash(String expression);

        SqlPiece dollar(String expression);

        /**
         * Resolves a {@code <foreach>} collection against the mapper method's
         * signature and puts its {@code item}/{@code index} names in scope, so
         * that {@link #hash} inside the body resolves to the loop variable.
         * Balanced by {@link #exitForeach()}, which is what returns the plan:
         * only once the body has been lowered is it known which of the loop's
         * variables it actually reads, and a generated body should not declare
         * the ones it does not.
         */
        void enterForeach(DynNode.Foreach node);

        ForeachPlan exitForeach();
    }

    /**
     * Everything about a {@code <foreach>} that needs the type system, which
     * lives in the frontend; the lowering supplies the structure around it.
     * Mirrors {@link SqlPiece.Foreach} minus the parts this class computes
     * (open/separator/close literals and the lowered body).
     */
    public record ForeachPlan(String label, String accessor, String collectionTypeFqn,
                              String sizeExpr,
                              SqlPiece.Foreach.Iteration iteration, String loopVar,
                              String loopVarTypeFqn, List<SqlPiece.Foreach.Local> locals,
                              String counterVar, boolean pad) {
    }

    /** Frontend callback compiling a {@code test} attribute to a Java boolean expression. */
    public interface TestCompiler {
        String compile(String test);
    }

    /**
     * The lowering result. When {@code dynamic()} is false the statement had
     * no dynamic structure left after folding and takes the static path with
     * {@code flatPieces()} as its SQL.
     */
    public record Lowered(DynamicModel model, List<SqlPiece> flatPieces) {

        public boolean dynamic() {
            return model != null;
        }
    }

    private final TokenLowerer tokens;
    private final TestCompiler tests;
    private final List<DynamicModel.CondLocal> locals = new ArrayList<>();
    private int nextLocal;

    private DynamicLowering(TokenLowerer tokens, TestCompiler tests) {
        this.tokens = tokens;
        this.tests = tests;
    }

    public static Lowered lower(List<DynNode> roots, TokenLowerer tokens, TestCompiler tests) {
        DynamicLowering lowering = new DynamicLowering(tokens, tests);
        List<Seg> segments = lowering.lowerList(roots, null);
        segments.removeIf(seg -> seg.pieces.isEmpty());
        bakeSeparators(segments);
        mergeAdjacent(segments);
        stripLeadingForeachSpace(segments);

        List<SqlPiece> flat = new ArrayList<>();
        segments.forEach(seg -> flat.addAll(seg.pieces));
        if (segments.size() <= 1 && lowering.locals.isEmpty()
                && flat.stream().noneMatch(p -> p instanceof SqlPiece.Foreach)) {
            return new Lowered(null, flat); // fully folded: static statement
        }
        List<DynamicModel.Segment> out = segments.stream()
                .map(seg -> new DynamicModel.Segment(seg.guard, List.copyOf(seg.pieces)))
                .toList();
        return new Lowered(new DynamicModel(List.copyOf(lowering.locals), out), flat);
    }

    // --- tree walk -----------------------------------------------------------------

    /** A segment under construction; guard == null means always present. */
    private static final class Seg {
        final String guard;
        final List<SqlPiece> pieces = new ArrayList<>();

        Seg(String guard) {
            this.guard = guard;
        }
    }

    private List<Seg> lowerList(List<DynNode> nodes, String guard) {
        List<Seg> out = new ArrayList<>();
        Seg open = null;
        for (DynNode node : nodes) {
            if (node instanceof DynNode.Text text) {
                if (open == null) {
                    open = new Seg(guard);
                }
                for (SqlTokenizer.RawToken token : SqlTokenizer.tokenize(text.raw())) {
                    if (token instanceof SqlTokenizer.RawToken.Text t) {
                        open.pieces.add(new SqlPiece.Text(t.text()));
                    } else if (token instanceof SqlTokenizer.RawToken.Hash h) {
                        open.pieces.add(tokens.hash(h.expression()));
                    } else if (token instanceof SqlTokenizer.RawToken.Dollar d) {
                        open.pieces.add(tokens.dollar(d.expression()));
                    }
                }
                continue;
            }
            if (node instanceof DynNode.Foreach foreach) {
                // A <foreach> always contributes text (its body is statically
                // non-blank and an empty collection throws), so it stays a
                // piece of the surrounding segment rather than opening a new
                // one — the guard structure around it is unchanged.
                if (open == null) {
                    open = new Seg(guard);
                }
                open.pieces.add(lowerForeach(foreach));
                continue;
            }
            if (open != null) {
                normalize(open);
                out.add(open);
                open = null;
            }
            if (node instanceof DynNode.If ifNode) {
                String local = newLocal(guard, ifNode.test());
                out.addAll(lowerList(ifNode.children(), local));
            } else if (node instanceof DynNode.Choose choose) {
                StringBuilder notPrevious = new StringBuilder();
                for (DynNode.Choose.When when : choose.whens()) {
                    String local = newLocal(
                            joinAnd(guard, notPrevious.toString()), when.test());
                    out.addAll(lowerList(when.children(), local));
                    notPrevious.append(notPrevious.isEmpty() ? "" : " && ").append('!').append(local);
                }
                if (!choose.otherwise().isEmpty()) {
                    String expr = joinAnd(guard, notPrevious.toString());
                    String local = "c" + nextLocal++;
                    locals.add(new DynamicModel.CondLocal(local, expr.isEmpty() ? "true" : expr));
                    out.addAll(lowerList(choose.otherwise(), local));
                }
            } else if (node instanceof DynNode.Trim trim) {
                List<Seg> inner = lowerList(trim.children(), guard);
                inner.removeIf(seg -> seg.pieces.isEmpty());
                out.addAll(foldTrim(trim, inner, guard));
            }
        }
        if (open != null) {
            normalize(open);
            out.add(open);
        }
        return out;
    }

    /**
     * Lowers one {@code <foreach>}. The literals carry the
     * spacing {@code DynamicContext}'s {@code StringJoiner(" ")} would have
     * produced: {@code open}, the separator, each iteration's body and
     * {@code close} are separate {@code appendSql} calls in MyBatis
     * (mybatis-3 {@code ForEachSqlNode.apply} and
     * {@code DynamicContext.appendSql}), so each gets exactly one leading
     * space — which is why the generated SQL reads {@code IN ( ? , ? )}.
     */
    private SqlPiece lowerForeach(DynNode.Foreach node) {
        tokens.enterForeach(node);
        List<Seg> bodySegments;
        ForeachPlan plan;
        try {
            bodySegments = lowerList(node.children(), null);
        } finally {
            plan = tokens.exitForeach();
        }
        bodySegments.removeIf(seg -> seg.pieces.isEmpty());
        List<SqlPiece> body = new ArrayList<>();
        bodySegments.forEach(seg -> body.addAll(seg.pieces));
        if (!body.isEmpty() && body.get(0) instanceof SqlPiece.Text text) {
            body.set(0, new SqlPiece.Text(" " + text.sql()));
        } else {
            body.add(0, new SqlPiece.Text(" "));
        }
        return new SqlPiece.Foreach(plan.label(), plan.accessor(), plan.collectionTypeFqn(),
                plan.sizeExpr(),
                plan.iteration(), plan.loopVar(), plan.loopVarTypeFqn(), plan.locals(),
                plan.counterVar(), joinerSpace(node.open()), joinerSpace(node.separator()),
                joinerSpace(node.close()), List.copyOf(body), plan.pad());
    }

    /**
     * One {@code appendSql} unit's worth of leading space. A literal that is
     * only whitespace contributes nothing the joiner space does not already
     * provide, so it is dropped rather than emitted as a second space —
     * {@code separator=" "} is the case that shows up in practice.
     */
    private static String joinerSpace(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        return " " + literal.trim();
    }

    /** One condition local: {@code boolean cN = <parent> && (<compiled test>);}. */
    private String newLocal(String parentGuard, String test) {
        String compiled = tests.compile(test);
        String expr = parentGuard == null || parentGuard.isEmpty()
                ? compiled
                : parentGuard + " && (" + compiled + ")";
        String name = "c" + nextLocal++;
        locals.add(new DynamicModel.CondLocal(name, expr));
        return name;
    }

    private static String joinAnd(String guard, String more) {
        if (guard == null || guard.isEmpty()) {
            return more;
        }
        return more.isEmpty() ? guard : guard + " && " + more;
    }

    // --- trim folding (TrimSqlNode.applyAll, resolved at build time) ------------------

    private List<Seg> foldTrim(DynNode.Trim trim, List<Seg> inner, String outerGuard) {
        if (inner.isEmpty()) {
            return inner;
        }
        String contentGuard = contentGuard(inner, outerGuard);

        // prefix overrides: strip the leading token of whichever segment
        // contributes first (WhereSqlNode's AND/OR stripping)
        if (!trim.prefixOverrides().isEmpty()) {
            List<String> before = new ArrayList<>();
            for (Seg seg : inner) {
                stripEdge(seg, trim.prefixOverrides(), guardOr(before), true);
                if (isUnconditional(seg, outerGuard)) {
                    break; // always present: nothing after it can be first
                }
                before.add(seg.guard);
            }
        }
        if (!trim.suffixOverrides().isEmpty()) {
            List<String> after = new ArrayList<>();
            for (int i = inner.size() - 1; i >= 0; i--) {
                Seg seg = inner.get(i);
                stripEdge(seg, trim.suffixOverrides(), guardOr(after), false);
                if (isUnconditional(seg, outerGuard)) {
                    break;
                }
                after.add(seg.guard);
            }
        }

        List<Seg> out = new ArrayList<>();
        if (trim.prefix() != null) {
            Seg prefix = new Seg(contentGuard);
            prefix.pieces.add(new SqlPiece.Text(trim.prefix()));
            out.add(prefix);
        }
        out.addAll(inner);
        if (trim.suffix() != null) {
            Seg suffix = new Seg(contentGuard);
            suffix.pieces.add(new SqlPiece.Text(trim.suffix()));
            out.add(suffix);
        }
        return out;
    }

    /**
     * Strips one override from a segment edge. With no possible earlier
     * (resp. later) contributor the strip is unconditional; otherwise the
     * kept token becomes an {@link SqlPiece.Alt} on "did any of them fire".
     * Overrides are matched in list order on the uppercased text, exactly
     * like TrimSqlNode.applyPrefix/applySuffix.
     */
    private static void stripEdge(Seg seg, List<String> overrides, String neighborGuard,
            boolean leading) {
        int at = leading ? 0 : seg.pieces.size() - 1;
        if (seg.pieces.get(at) instanceof SqlPiece.Foreach foreach) {
            // TrimSqlNode strips against the assembled text of the whole trim
            // body, which includes a <foreach>'s open and close — so a loop at
            // the edge is not exempt (TrimSqlNode.applyAll/applyPrefix)
            stripForeachEdge(seg, at, foreach, overrides, neighborGuard, leading);
            return;
        }
        if (!(seg.pieces.get(at) instanceof SqlPiece.Text text)) {
            return; // a bind/splice at the edge: no override can match
        }
        String upper = text.sql().toUpperCase(Locale.ENGLISH);
        for (String override : overrides) {
            String needle = (leading ? override : override.stripTrailing())
                    .toUpperCase(Locale.ENGLISH);
            if (needle.isEmpty()
                    || !(leading ? upper.startsWith(needle) : upper.endsWith(needle))) {
                continue;
            }
            String kept;
            String rest;
            if (leading) {
                String stripped = text.sql().substring(needle.length()).stripLeading();
                kept = text.sql().substring(0, text.sql().length() - stripped.length());
                rest = stripped;
            } else {
                String stripped = text.sql()
                        .substring(0, text.sql().length() - needle.length()).stripTrailing();
                kept = text.sql().substring(stripped.length());
                rest = stripped;
            }
            List<SqlPiece> replacement = new ArrayList<>();
            if (neighborGuard == null) {
                // nothing conditional on that side: always stripped
                if (!rest.isEmpty()) {
                    replacement.add(new SqlPiece.Text(rest));
                }
            } else if (leading) {
                replacement.add(new SqlPiece.Alt(neighborGuard, kept, ""));
                if (!rest.isEmpty()) {
                    replacement.add(new SqlPiece.Text(rest));
                }
            } else {
                if (!rest.isEmpty()) {
                    replacement.add(new SqlPiece.Text(rest));
                }
                replacement.add(new SqlPiece.Alt(neighborGuard, kept, ""));
            }
            seg.pieces.remove(at);
            seg.pieces.addAll(at, replacement);
            return;
        }
    }

    /**
     * The trim-edge strip when the edge piece is a {@code <foreach>}: the
     * token lives in the loop's {@code open} (leading) or {@code close}
     * (trailing), which are emitted once, so stripping them is exactly what
     * MyBatis does to the assembled body.
     *
     * <p>Two shapes are refused instead of mis-generated. Without an
     * {@code open}/{@code close} the matching token sits in the body, which
     * is emitted once *per element* — MyBatis strips only the first (resp.
     * last) occurrence, and there is no build-time text that means "the first
     * iteration only". And with a conditional neighbour the strip is a
     * runtime decision, which {@link SqlPiece.Alt} can express for text but
     * not for a loop's fixed literals.
     */
    private static void stripForeachEdge(Seg seg, int at, SqlPiece.Foreach foreach,
            List<String> overrides, String neighborGuard, boolean leading) {
        String literal = leading ? foreach.open() : foreach.close();
        String edge = literal != null ? literal : bodyEdgeText(foreach, leading);
        if (edge == null) {
            return;
        }
        String lead = edge.substring(0, edge.length() - edge.stripLeading().length());
        String core = leading ? edge.stripLeading() : edge;
        String upper = core.toUpperCase(Locale.ENGLISH);
        for (String override : overrides) {
            String needle = (leading ? override : override.stripTrailing())
                    .toUpperCase(Locale.ENGLISH);
            if (needle.isEmpty()
                    || !(leading ? upper.startsWith(needle) : upper.endsWith(needle))) {
                continue;
            }
            String what = "<foreach collection=\"" + foreach.label() + "\"> starts with \""
                    + override.trim() + "\"";
            if (literal == null) {
                throw new LarkBatisProcessingException(null, what
                        + " in its body, which the surrounding <where>/<set>/<trim> would strip"
                        + " from the first element only. Move that token into the loop's "
                        + (leading ? "open" : "close") + " attribute.");
            }
            if (neighborGuard != null) {
                throw new LarkBatisProcessingException(null, what
                        + ", and whether the surrounding <where>/<set>/<trim> strips it depends"
                        + " on a condition next to the loop. Put the loop first in the trim, or"
                        + " move the token out of the loop.");
            }
            String stripped = leading
                    ? lead + core.substring(needle.length()).stripLeading()
                    : core.substring(0, core.length() - needle.length()).stripTrailing();
            String replacement = stripped.isBlank() ? null : stripped;
            seg.pieces.set(at, leading
                    ? withOpen(foreach, replacement)
                    : withClose(foreach, replacement));
            return;
        }
    }

    /** The loop's first (resp. last) body text, when it has no open/close. */
    private static String bodyEdgeText(SqlPiece.Foreach foreach, boolean leading) {
        List<SqlPiece> body = foreach.body();
        if (body.isEmpty()) {
            return null;
        }
        SqlPiece piece = leading ? body.get(0) : body.get(body.size() - 1);
        return piece instanceof SqlPiece.Text text ? text.sql() : null;
    }

    /** OR of the guards seen so far, or null when none (edge segment). */
    private static String guardOr(List<String> guards) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String guard : guards) {
            distinct.add(guard == null ? null : guard);
        }
        if (distinct.isEmpty()) {
            return null;
        }
        return String.join(" | ", distinct);
    }

    private static boolean isUnconditional(Seg seg, String outerGuard) {
        return seg.guard == null ? outerGuard == null : seg.guard.equals(outerGuard);
    }

    /** The trim's "content is non-empty" condition (prefix/suffix presence). */
    private static String contentGuard(List<Seg> inner, String outerGuard) {
        Set<String> guards = new LinkedHashSet<>();
        for (Seg seg : inner) {
            if (isUnconditional(seg, outerGuard)) {
                return outerGuard;
            }
            guards.add(seg.guard);
        }
        return String.join(" | ", guards);
    }

    // --- whitespace normalization ------------------------------------------------------

    /** Collapses whitespace runs and trims the segment's outer edges. */
    private static void normalize(Seg seg) {
        for (int i = 0; i < seg.pieces.size(); i++) {
            if (seg.pieces.get(i) instanceof SqlPiece.Text text) {
                seg.pieces.set(i, new SqlPiece.Text(text.sql().replaceAll("\\s+", " ")));
            }
        }
        // A <foreach> is its own run of appendSql units and carries the joiner
        // space on each of them, so the text around it must not add a second.
        for (int i = 0; i < seg.pieces.size(); i++) {
            if (!(seg.pieces.get(i) instanceof SqlPiece.Foreach)) {
                continue;
            }
            if (i > 0 && seg.pieces.get(i - 1) instanceof SqlPiece.Text before) {
                seg.pieces.set(i - 1, new SqlPiece.Text(before.sql().stripTrailing()));
            }
            if (i + 1 < seg.pieces.size()
                    && seg.pieces.get(i + 1) instanceof SqlPiece.Text after) {
                String text = after.sql().stripLeading();
                seg.pieces.set(i + 1, new SqlPiece.Text(text.isEmpty() ? "" : " " + text));
            }
        }
        if (!seg.pieces.isEmpty() && seg.pieces.get(0) instanceof SqlPiece.Text first) {
            seg.pieces.set(0, new SqlPiece.Text(first.sql().stripLeading()));
        }
        int last = seg.pieces.size() - 1;
        if (last >= 0 && seg.pieces.get(last) instanceof SqlPiece.Text text) {
            seg.pieces.set(last, new SqlPiece.Text(text.sql().stripTrailing()));
        }
        seg.pieces.removeIf(p -> p instanceof SqlPiece.Text t && t.sql().isEmpty());
    }

    /** One space in front of every segment after the first (the StringJoiner shape). */
    private static void bakeSeparators(List<Seg> segments) {
        for (int i = 1; i < segments.size(); i++) {
            List<SqlPiece> pieces = segments.get(i).pieces;
            SqlPiece first = pieces.get(0);
            if (first instanceof SqlPiece.Text text) {
                pieces.set(0, new SqlPiece.Text(" " + text.sql()));
            } else if (first instanceof SqlPiece.Alt alt) {
                pieces.set(0, new SqlPiece.Alt(alt.condition(),
                        " " + alt.whenTrue(), " " + alt.whenFalse()));
            } else if (!(first instanceof SqlPiece.Foreach)) {
                // a <foreach> already carries the joiner space on its own
                // first appendSql unit
                pieces.add(0, new SqlPiece.Text(" "));
            }
        }
    }

    /**
     * A statement that opens with a {@code <foreach>} has no preceding
     * {@code appendSql} to be joined to, so its first unit carries no space —
     * the same edge {@code normalize} trims for a leading text piece.
     */
    private static void stripLeadingForeachSpace(List<Seg> segments) {
        if (segments.isEmpty() || segments.get(0).pieces.isEmpty()) {
            return;
        }
        if (!(segments.get(0).pieces.get(0) instanceof SqlPiece.Foreach foreach)) {
            return;
        }
        if (foreach.open() != null) {
            segments.get(0).pieces.set(0, withOpen(foreach, foreach.open().stripLeading()));
        }
        // With no open the leading space lives in the body, which is emitted
        // once per element — stripping it there would delete the separator
        // space of every iteration. The emitter trims the assembled SQL
        // instead, exactly as DynamicContext.getSql() does.
    }

    private static SqlPiece.Foreach withOpen(SqlPiece.Foreach f, String open) {
        return new SqlPiece.Foreach(f.label(), f.accessor(), f.collectionTypeFqn(),
                f.sizeExpr(), f.iteration(),
                f.loopVar(), f.loopVarTypeFqn(), f.locals(), f.counterVar(), open,
                f.separator(), f.close(), f.body(), f.pad());
    }

    private static SqlPiece.Foreach withClose(SqlPiece.Foreach f, String close) {
        return new SqlPiece.Foreach(f.label(), f.accessor(), f.collectionTypeFqn(),
                f.sizeExpr(), f.iteration(),
                f.loopVar(), f.loopVarTypeFqn(), f.locals(), f.counterVar(), f.open(),
                f.separator(), close, f.body(), f.pad());
    }

    private static SqlPiece.Foreach withBody(SqlPiece.Foreach f, List<SqlPiece> body) {
        return new SqlPiece.Foreach(f.label(), f.accessor(), f.collectionTypeFqn(),
                f.sizeExpr(), f.iteration(),
                f.loopVar(), f.loopVarTypeFqn(), f.locals(), f.counterVar(), f.open(),
                f.separator(), f.close(), List.copyOf(body), f.pad());
    }

    /**
     * Cosmetic: merges neighbors so the emitted body reads as one decision per
     * branch — {@code sb.append(c0 ? " AND age >= ?" : " age >= ?")} instead of
     * two appends. Also coalesces segments that ended up with equal guards.
     */
    private static void mergeAdjacent(List<Seg> segments) {
        for (int i = 0; i + 1 < segments.size(); i++) {
            Seg a = segments.get(i);
            Seg b = segments.get(i + 1);
            boolean sameGuard = a.guard == null ? b.guard == null : a.guard.equals(b.guard);
            if (sameGuard) {
                a.pieces.addAll(b.pieces);
                segments.remove(i + 1);
                i--;
            }
        }
        for (Seg seg : segments) {
            for (int i = 0; i + 1 < seg.pieces.size(); i++) {
                SqlPiece a = seg.pieces.get(i);
                SqlPiece b = seg.pieces.get(i + 1);
                SqlPiece merged = null;
                if (a instanceof SqlPiece.Text ta && b instanceof SqlPiece.Text tb) {
                    merged = new SqlPiece.Text(ta.sql() + tb.sql());
                } else if (a instanceof SqlPiece.Alt alt && b instanceof SqlPiece.Text tb) {
                    merged = new SqlPiece.Alt(alt.condition(),
                            alt.whenTrue() + tb.sql(), alt.whenFalse() + tb.sql());
                } else if (a instanceof SqlPiece.Text ta && b instanceof SqlPiece.Alt alt) {
                    merged = new SqlPiece.Alt(alt.condition(),
                            ta.sql() + alt.whenTrue(), ta.sql() + alt.whenFalse());
                }
                if (merged != null) {
                    seg.pieces.set(i, merged);
                    seg.pieces.remove(i + 1);
                    i--;
                }
            }
        }
    }
}
