package io.github.lightbatis.processor.ir;

import java.util.List;

/**
 * Generated-key handling for one INSERT (design §07).
 *
 * @param columns     explicit key column names passed to
 *                    {@code prepareStatement(sql, String[])}; empty means the
 *                    build already warned and the code falls back to
 *                    {@code RETURN_GENERATED_KEYS}
 * @param assignments one per key column position, in order
 */
public record KeyModel(List<String> columns, List<Assignment> assignments) {

    /**
     * Assign generated-key column {@code position} (1-based) onto the target.
     *
     * @param targetAccessor expression for the bean the key lands on, e.g.
     *                       {@code "u"} (batch: the loop variable)
     * @param setterName     setter on that bean
     * @param kind           read strategy for {@code getGeneratedKeys()}
     */
    public record Assignment(String targetAccessor, String setterName, ValueKind kind) {
    }
}
