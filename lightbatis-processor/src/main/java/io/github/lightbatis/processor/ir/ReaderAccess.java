package io.github.lightbatis.processor.ir;

import java.util.List;

/**
 * How one SELECT statement reaches its row reader (design §04).
 *
 * @param mode         see {@link Mode}
 * @param columnOrder  for {@link Mode#POSITIONAL_CUSTOM}: for each property of
 *                     the result class (declaration order), the 1-based
 *                     ResultSet column position, or 0 when the statement does
 *                     not select that property
 * @param downgradeReason human-readable reason when {@code mode == NAME_BASED};
 *                     printed at build time (design §04, §08)
 */
public record ReaderAccess(Mode mode, List<Integer> columnOrder, String downgradeReason) {

    public enum Mode {
        /** Select list matches the canonical property order exactly → {@code read(rs)}. */
        POSITIONAL_CANONICAL,
        /** Select list parsed but reordered/partial → constant int[] + {@code read(rs, cols)}. */
        POSITIONAL_CUSTOM,
        /** Select list not parseable → indexes resolved once from metadata on the first row. */
        NAME_BASED
    }

    public static ReaderAccess canonical() {
        return new ReaderAccess(Mode.POSITIONAL_CANONICAL, List.of(), null);
    }

    public static ReaderAccess custom(List<Integer> columnOrder) {
        return new ReaderAccess(Mode.POSITIONAL_CUSTOM, columnOrder, null);
    }

    public static ReaderAccess nameBased(String reason) {
        return new ReaderAccess(Mode.NAME_BASED, List.of(), reason);
    }
}
