package io.github.larkbatis.processor.ir;

import java.util.Locale;

/**
 * How a ResultSet column label is matched to a property — the build-time
 * equivalent of MyBatis's {@code mapUnderscoreToCamelCase} setting, resolved
 * once for the whole compilation.
 *
 * <p>MyBatis decides this per lookup in {@code MetaClass.findProperty(name,
 * useCamelCaseMapping)}: with the setting on it removes the underscores from
 * the <em>column</em> name and then looks the property up case-insensitively;
 * with it off it looks the column name up as written. Here the same choice is
 * made once and baked into the generated reader, which is why the two modes
 * emit different code rather than reading a flag at runtime.
 *
 * <p>LarkBatis normalizes both sides, so a property or {@code @Column} spelled
 * {@code usr_email} still matches a label spelled {@code usrEmail} under
 * {@link #UNDERSCORE_TO_CAMEL_CASE}. MyBatis strips only the label, which makes
 * that one pairing miss. The difference is deliberate: a name written with
 * underscores on either side means the same column to a reader, and refusing
 * to match it would be a trap rather than a rule.
 */
public enum ColumnNaming {

    /**
     * The default. Underscores are ignored on both sides, so {@code created_at}
     * reaches {@code setCreatedAt}. This is what a Spring Boot service running
     * {@code map-underscore-to-camel-case: true} already has.
     */
    UNDERSCORE_TO_CAMEL_CASE,

    /**
     * Underscores are significant: a label matches only a property or
     * {@code @Column} spelled the same way, case aside. MyBatis's own default,
     * and what a codebase relying on {@code SELECT user_name AS userName}
     * aliases or an explicit {@code <resultMap>} was written against — under
     * the other mode those mappers still work, but columns MyBatis left unset
     * start being populated, which is a behaviour change no test would name.
     */
    EXACT;

    /** The default when the build says nothing. */
    public static final ColumnNaming DEFAULT = UNDERSCORE_TO_CAMEL_CASE;

    public static ColumnNaming of(boolean mapUnderscoreToCamelCase) {
        return mapUnderscoreToCamelCase ? UNDERSCORE_TO_CAMEL_CASE : EXACT;
    }

    /**
     * The key a column name and a property name are compared by. Applied to
     * both sides, so it has to be a function of the name alone.
     */
    public String keyOf(String columnName) {
        String key = this == UNDERSCORE_TO_CAMEL_CASE ? columnName.replace("_", "") : columnName;
        return key.toLowerCase(Locale.ROOT);
    }

    /** Whether underscores are removed before comparing — what generated readers ask. */
    public boolean ignoresUnderscores() {
        return this == UNDERSCORE_TO_CAMEL_CASE;
    }
}
