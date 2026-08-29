package io.github.lightbatis.processor.ir;

import java.util.List;

/**
 * One piece of a lowered SQL statement, in source order. The whole point of
 * the build-time cut (design §02): by the time the IR exists, {@code #{}} has
 * become a positional {@code ?} plus a typed binding, and {@code ${}} has
 * become a typed splice — the SQL text is never parsed again at runtime.
 */
public sealed interface SqlPiece {

    /** Literal SQL text, emitted as a string constant. */
    record Text(String sql) implements SqlPiece {
    }

    /**
     * A {@code #{}} occurrence: a {@code ?} in the SQL plus one typed bind.
     *
     * @param accessor Java expression producing the value, e.g. {@code "id"}
     *                 or {@code "u.getName()"} (batch: against the loop var)
     * @param kind     how to move the value onto the PreparedStatement
     * @param enumType enum FQN when {@code kind == ENUM}, else null
     */
    record Bind(String expression, String accessor, ValueKind kind, String enumType) implements SqlPiece {
    }

    /**
     * A {@code ${}} occurrence, already vetted at build time (design §08):
     * only SqlFragment, closed-value types, or @OrderBy parameters get here.
     */
    record Dollar(String expression, DollarKind dollarKind, String accessor,
                  List<String> allowed) implements SqlPiece {

        public enum DollarKind {
            /** SqlFragment parameter: spliced via {@code x.text()}. */
            FRAGMENT,
            /** Closed-value type (int/long/short/byte/boolean): spliced via string concat. */
            CLOSED_VALUE,
            /** Enum: spliced via {@code x.name()}. */
            ENUM_NAME,
            /** {@code @OrderBy(allowed=...)} String parameter: a generated switch over the literals. */
            ORDER_BY
        }
    }
}
