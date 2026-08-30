package io.github.larkbatis.processor.frontend.dyn;

import java.util.List;

/**
 * The dynamic-SQL tag tree of one XML statement, exactly as parsed —
 * MyBatis's SqlNode tree without the runtime. {@code <where>}
 * and {@code <set>} arrive here already desugared to {@link Trim} with the
 * fixed override lists from {@code WhereSqlNode}/{@code SetSqlNode}.
 */
public sealed interface DynNode {

    /** Raw text (may contain {@code #{}} and {@code ${}} tokens). */
    record Text(String raw) implements DynNode {
    }

    /** {@code <if test="...">}. */
    record If(String test, List<DynNode> children) implements DynNode {
    }

    /** {@code <choose>}: first matching {@code <when>} wins, else {@code <otherwise>}. */
    record Choose(List<When> whens, List<DynNode> otherwise) implements DynNode {

        public record When(String test, List<DynNode> children) {
        }
    }

    /**
     * {@code <foreach>}. Attributes are literal constants, like
     * {@link Trim}'s; {@code collection} is a parameter path resolved against
     * the mapper method's signature, not an OGNL expression.
     *
     * @param collection collection expression (a property path)
     * @param item       name bound to each element inside the body, or null
     * @param index      name bound to the position — or, for a {@code Map},
     *                   the entry key — inside the body, or null
     */
    record Foreach(String collection, String item, String index,
                   String open, String separator, String close,
                   List<DynNode> children) implements DynNode {
    }

    /**
     * {@code <trim>} (and the {@code <where>}/{@code <set>} sugar). Attributes
     * are literal constants — that is what makes the
     * build-time fold possible at all.
     */
    record Trim(String prefix, List<String> prefixOverrides,
                String suffix, List<String> suffixOverrides,
                List<DynNode> children) implements DynNode {

        /** The fixed prefix list of WhereSqlNode; whitespace variants collapse under normalization. */
        public static Trim where(List<DynNode> children) {
            return new Trim("WHERE", List.of("AND ", "OR "), null, List.of(), children);
        }

        /** SetSqlNode: strips a leading and a trailing comma (mybatis-3 SetSqlNode.java). */
        public static Trim set(List<DynNode> children) {
            return new Trim("SET", List.of(","), null, List.of(","), children);
        }
    }
}
