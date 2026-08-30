package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * A one-level nested result map: {@code <association>} or {@code <collection>}
 * filled from a join, with the ResultSet ordered by the parent key.
 * One level only — no recursion, no cycles.
 *
 * <p>MyBatis solves the same problem with a {@code CacheKey} built per row by
 * reflecting over the result map's id columns and hashing them. Here the
 * grouping loop is written out: the parent key columns are read into typed
 * locals and compared with {@code ==} or {@code equals}, which is all a
 * {@code CacheKey} was ever standing in for.
 *
 * <p>Positions are not stored — {@link KeyProperty#index} points into the
 * result class's property list, and the {@link ReaderAccess} of the parent (or
 * of the child) says where that property's column is. That keeps one source of
 * truth for column positions across all four reader modes.
 *
 * @param kind         association (one child object) or collection (a List)
 * @param property     the parent property being filled, for build messages
 * @param setterName   setter on the parent bean
 * @param childFqn     child result class FQN — a key into the ResultModel registry
 * @param childAccess  how the child's columns are reached
 * @param parentKeys   the parent's {@code <id>} properties, in declaration order
 * @param childKey     the child's first {@code <id>} property; its column being
 *                     NULL is what a LEFT JOIN miss looks like
 */
public record NestedResult(Kind kind, String property, String setterName, String childFqn,
                           ReaderAccess childAccess, List<KeyProperty> parentKeys,
                           KeyProperty childKey) {

    public enum Kind {
        ASSOCIATION,
        COLLECTION
    }

    /**
     * One key property: where it sits in the result class's property list and
     * how to read it back out of the ResultSet.
     *
     * @param index    position in the result class's property list
     * @param kind     JDBC move strategy, which also decides {@code ==} vs {@code equals}
     * @param enumType enum FQN when {@code kind == ENUM}, else null
     */
    public record KeyProperty(int index, ValueKind kind, String enumType) {
    }
}
