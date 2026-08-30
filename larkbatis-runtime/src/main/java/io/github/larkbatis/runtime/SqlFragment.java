package io.github.larkbatis.runtime;

import java.util.regex.Pattern;

/**
 * A piece of raw SQL text that a call site deliberately provides. This is the
 * official mechanism behind {@code ${}} and the manual escape
 * hatch: a {@code String} never reaches the SQL text directly.
 *
 * <p>Only three factories exist, and none of them can produce an empty
 * fragment. {@link #unsafeRawSql} is the single audit point of the whole
 * system — {@code grep -rn "unsafeRawSql" src/} lists every place arbitrary
 * text enters SQL.
 */
public final class SqlFragment {

    private static final Pattern IDENT = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?");

    private final String text;

    private SqlFragment(String text) {
        this.text = text;
    }

    /** The raw SQL text this fragment carries. */
    public String text() {
        return text;
    }

    /**
     * Whitelist factory. The number of SQL variants is bounded by the
     * whitelist size, which keeps driver statement caches safe.
     */
    public static SqlFragment allowed(String value, String... allowed) {
        for (String a : allowed) {
            if (a.equals(value)) {
                return new SqlFragment(a);
            }
        }
        throw new LarkBatisRejectedException(value, allowed);
    }

    /**
     * Accepts a plain SQL identifier ({@code name} or {@code schema.name}),
     * which covers most real-world {@code ${}} uses.
     */
    public static SqlFragment identifier(String value) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new LarkBatisRejectedException(value);
        }
        return new SqlFragment(value);
    }

    /**
     * No validation at all. The name is deliberately ugly so it can be
     * grepped; a code-review or CI rule on this one name covers every raw-SQL
     * injection point in the codebase.
     */
    public static SqlFragment unsafeRawSql(String value) {
        if (value == null || value.isBlank()) {
            throw new LarkBatisRejectedException(value);
        }
        return new SqlFragment(value);
    }

    @Override
    public String toString() {
        return "SqlFragment[" + text + "]";
    }
}
