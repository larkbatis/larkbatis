package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * The dynamic-SQL shape of one statement, fully resolved at build time
 * — the only things left for runtime are the boolean results of
 * the {@code <if>}/{@code <when>} conditions. Everything structural — which
 * fragment follows which, where {@code <where>/<set>/<trim>} prefixes and
 * overrides land — is already folded into the segments.
 *
 * <p>Null on {@link StatementModel#dynamic()} means the statement is static
 * and compiles to a single SQL constant.
 *
 * @param locals   condition locals in evaluation order; each is emitted once
 *                 as {@code boolean cN = <javaExpr>;} and reused for both SQL
 *                 assembly and parameter binding
 * @param segments SQL fragments in source order
 */
public record DynamicModel(List<CondLocal> locals, List<Segment> segments) {

    /**
     * One condition local. {@code javaExpr} is a complete Java boolean
     * expression over the mapper method's parameters and previously declared
     * locals (nested tags conjoin the enclosing local for short-circuiting:
     * {@code c1 = c0 && ...}).
     */
    public record CondLocal(String name, String javaExpr) {
    }

    /**
     * A run of SQL pieces appended together under one guard.
     *
     * @param guard  Java boolean expression over condition locals; null means
     *               the segment is always present
     * @param pieces text/bind/splice pieces, in order
     */
    public record Segment(String guard, List<SqlPiece> pieces) {
    }
}
