package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * One piece of a lowered SQL statement, in source order. The whole point of
 * the build-time cut: by the time the IR exists, {@code #{}} has
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
     * @param handler  FQN of a
     *                 {@code LarkBatisTypeHandler}
     *                 named by {@code @Handler} on the parameter or by a
     *                 {@code typeHandler} attribute inside the {@code #{}},
     *                 or null to bind with {@code kind}
     */
    record Bind(String expression, String accessor, ValueKind kind, String enumType,
                String handler) implements SqlPiece {
    }

    /**
     * Literal SQL text whose content depends on which dynamic branches are
     * present — the constant-folded form of {@code <where>/<set>/<trim>}
     * override stripping. {@code condition} is a Java boolean
     * expression over the statement's condition locals; when it is true at
     * runtime, some earlier (respectively later) content contributed, so the
     * un-stripped variant is used.
     *
     * @param condition Java boolean expression over condition locals
     * @param whenTrue  text when the condition holds (override kept)
     * @param whenFalse text when it does not (override stripped)
     */
    record Alt(String condition, String whenTrue, String whenFalse) implements SqlPiece {
    }

    /**
     * A {@code <foreach>} occurrence. The element count is the
     * one thing here that is not known at build time, so this is the only
     * piece that expands to a loop: one loop appends the placeholders, a
     * second binds the values, and the loop index alone connects them —
     * MyBatis's {@code __frch_*} naming layer has nothing to do.
     *
     * @param label      collection expression as written, for the
     *                   empty-collection error message
     * @param accessor   Java expression producing the collection. Evaluated
     *                   once: where it is not already a local, the emitter
     *                   reads it into one, because the placeholder loop and
     *                   the bind loop must walk the same object — a getter
     *                   answering twice would put a different number of
     *                   {@code ?} in the SQL than there are values to bind
     * @param collectionTypeFqn declared type of {@code accessor}, so that
     *                   local can be given a real type
     * @param sizeExpr   Java expression producing its element count, written
     *                   against {@code accessor}
     * @param iteration  how the loop is written and what {@code item}/
     *                   {@code index} mean
     * @param loopVar    the variable declared by the enhanced-for; equals
     *                   {@code item} except for map iteration, where it is
     *                   the entry
     * @param loopVarTypeFqn declared type of {@code loopVar}
     * @param locals     variables unpacked from {@code loopVar} at the top of
     *                   the loop body, in order (map iteration only)
     * @param counterVar an {@code int} the emitter declares before the loop
     *                   and increments at the end of each iteration, or null
     *                   when nothing references the position; this is the
     *                   {@code index} of a collection or array {@code
     *                   <foreach>}
     * @param open       literal emitted before the first element, or null
     * @param separator  literal emitted between elements, or null
     * @param close      literal emitted after the last element, or null
     * @param body       pieces of one iteration; binds address {@code item}/
     *                   {@code index} through {@code locals}
     * @param pad        pad the placeholder count to the next power of two,
     *                   repeating the last element; only ever
     *                   set for an IN-list shape, which the generator checks
     */
    record Foreach(String label, String accessor, String collectionTypeFqn, String sizeExpr,
                   Iteration iteration, String loopVar, String loopVarTypeFqn,
                   List<Local> locals, String counterVar,
                   String open, String separator, String close,
                   List<SqlPiece> body, boolean pad) implements SqlPiece {

        /**
         * Whether {@link #accessor()} is already a local of the generated
         * body — a mapper parameter — and so needs no hoisting. Anything with
         * a call or a dot in it is reached through a getter and is read once
         * into a local instead.
         */
        public boolean accessorIsLocal() {
            for (int i = 0; i < accessor.length(); i++) {
                char ch = accessor.charAt(i);
                if (!Character.isJavaIdentifierPart(ch)) {
                    return false;
                }
            }
            return !accessor.isEmpty();
        }

        public enum Iteration {
            /** {@code Collection<T>}: {@code item} is the element, {@code index} the position. */
            COLLECTION,
            /** {@code T[]}: as above, with {@code arr.length} for the size. */
            ARRAY,
            /** {@code Map<K,V>}: {@code index} is the key, {@code item} the value (issue #709). */
            MAP_ENTRY
        }

        /** One variable unpacked from the loop variable. */
        public record Local(String name, String typeFqn, String initExpr) {
        }
    }

    /**
     * A {@code ${}} occurrence, already vetted at build time:
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
