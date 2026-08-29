package io.github.lightbatis.processor.ir;

/**
 * One writable property of a result class, in declaration order. Declaration
 * order defines the canonical column order of the positional row reader.
 *
 * @param name       property name (e.g. {@code createdAt})
 * @param setterName setter method name (e.g. {@code setCreatedAt})
 * @param kind       JDBC move strategy
 * @param enumType   enum FQN when {@code kind == ENUM}, else null
 */
public record PropertyModel(String name, String setterName, ValueKind kind, String enumType) {

    /**
     * Key used to match select-list items and ResultSet labels to this
     * property: lower-cased with underscores stripped — the build-time
     * equivalent of MyBatis {@code mapUnderscoreToCamelCase}
     * (MetaClass.findProperty with useCamelCaseMapping).
     */
    public String matchKey() {
        return name.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static String matchKeyOf(String columnName) {
        return columnName.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }
}
