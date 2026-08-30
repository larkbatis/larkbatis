package io.github.larkbatis.processor.ir;

/**
 * One writable property of a result class, in declaration order. Declaration
 * order defines the canonical column order of the positional row reader.
 *
 * @param name       property name (e.g. {@code createdAt})
 * @param setterName setter method name (e.g. {@code setCreatedAt})
 * @param kind       JDBC move strategy
 * @param enumType   enum FQN when {@code kind == ENUM}, else null
 * @param column     column declared by {@code @Column}, or null when the
 *                   property name carries it through the naming convention
 * @param handler    FQN of a {@code LarkBatisTypeHandler}
 *                   named by {@code @Handler} or by a mapper XML
 *                   {@code typeHandler} attribute, or null to move the value
 *                   with {@code kind}. When set, {@code kind} is the strategy
 *                   the type would have had and is not what the reader emits
 */
public record PropertyModel(String name, String setterName, ValueKind kind, String enumType,
                            String column, String handler) {

    /**
     * Key used to match select-list items and ResultSet labels to this
     * property: lower-cased with underscores stripped — the build-time
     * equivalent of MyBatis {@code mapUnderscoreToCamelCase}
     * (MetaClass.findProperty with useCamelCaseMapping). An explicit
     * {@code @Column} replaces the property name here and is normalized the
     * same way, so it matches a label whatever its case.
     */
    public String matchKey() {
        return matchKeyOf(columnName());
    }

    /** What this property is called in the ResultSet: {@code @Column} or the property name. */
    public String columnName() {
        return column == null ? name : column;
    }

    public static String matchKeyOf(String columnName) {
        return columnName.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }
}
