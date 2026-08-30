package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * How one SELECT statement reaches its row reader.
 *
 * <p>The four modes are two axes crossed: whether the column positions are
 * known at build time, and whether the column each property comes from was
 * declared (a {@code <resultMap>}) or inferred from the property name.
 *
 * @param mode         see {@link Mode}
 * @param columnOrder  for {@link Mode#POSITIONAL_CUSTOM}: for each property of
 *                     the result class (declaration order), the 1-based
 *                     ResultSet column position, or 0 when the statement does
 *                     not select that property
 * @param columnNames  for {@link Mode#NAME_BASED_MAPPED}: for each property,
 *                     the column name a {@code <resultMap>} declared for it,
 *                     or null when the map does not mention that property
 * @param downgradeReason human-readable reason when the mode is name-based;
 *                     printed at build time
 */
public record ReaderAccess(Mode mode, List<Integer> columnOrder, List<String> columnNames,
                           String downgradeReason) {

    public enum Mode {
        /** Select list matches the canonical property order exactly → {@code read(rs)}. */
        POSITIONAL_CANONICAL,
        /** Select list parsed but reordered/partial → constant int[] + {@code read(rs, cols)}. */
        POSITIONAL_CUSTOM,
        /** Select list not parseable → indexes resolved once from metadata, matched by property name. */
        NAME_BASED,
        /**
         * Select list not parseable, but a {@code <resultMap>} named the
         * columns → indexes resolved once from metadata, matched by the
         * declared column names rather than by property name.
         */
        NAME_BASED_MAPPED
    }

    public static ReaderAccess canonical() {
        return new ReaderAccess(Mode.POSITIONAL_CANONICAL, List.of(), List.of(), null);
    }

    public static ReaderAccess custom(List<Integer> columnOrder) {
        return new ReaderAccess(Mode.POSITIONAL_CUSTOM, columnOrder, List.of(), null);
    }

    public static ReaderAccess nameBased(String reason) {
        return new ReaderAccess(Mode.NAME_BASED, List.of(), List.of(), reason);
    }

    public static ReaderAccess nameBasedMapped(List<String> columnNames, String reason) {
        return new ReaderAccess(Mode.NAME_BASED_MAPPED, List.of(), columnNames, reason);
    }

    /** Whether the positions are only known once a ResultSet is in hand. */
    public boolean isNameBased() {
        return mode == Mode.NAME_BASED || mode == Mode.NAME_BASED_MAPPED;
    }
}
