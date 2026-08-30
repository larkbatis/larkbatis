package io.github.larkbatis.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code #{...}} placeholders in a source file, split into the property
 * path and the options after it.
 *
 * <p>Shared by the XML and the Java scan because {@code #{}} means the same
 * thing in a mapper file and in a {@code @Select}, and the two rules that read
 * it — a path deeper than one property, and an inline {@code typeHandler=} —
 * would otherwise be written twice and drift once.
 */
final class BindPlaceholders {

    /** {@code #{...}} cannot nest, so refusing braces inside keeps this honest. */
    private static final Pattern HASH = Pattern.compile("#\\{\\s*([^{}]*?)\\s*}");

    /**
     * What a property path can actually look like: identifiers joined by dots,
     * each optionally indexed by a number or a name.
     *
     * <p>This is a filter, not a parse. A codebase that builds SQL in Java puts
     * {@code "... #{list[" + i + "].id} ..."} in a source file, and blanking
     * comments does not blank string concatenation — so the regex above happily
     * matches {@code list[").append(i).append("].id} and the dots in
     * {@code .append(} would read as a three-deep path. Every such fragment
     * fails here, and the statement it belongs to is already a blocker under
     * the {@code @SelectProvider} rule.
     */
    private static final Pattern PROPERTY_PATH = Pattern.compile(
            "[A-Za-z_$][\\w$]*(?:\\[\\w*])*(?:\\.[A-Za-z_$][\\w$]*(?:\\[\\w*])*)*");

    private static final String TYPE_HANDLER_OPTION = "typeHandler";

    private BindPlaceholders() {
    }

    /**
     * @param line    1-based line of the placeholder
     * @param text    the placeholder as written, for the report
     * @param path    the property path before the first comma
     * @param options everything after it, {@code jdbcType=VARCHAR} style
     */
    record Placeholder(int line, String text, String path, List<String> options) {

        /**
         * Whether the path needs more than the single property hop the
         * generator resolves. Three segments is the threshold either way:
         * {@code #{u.name}} is one hop against a bean and one hop against
         * {@code @Param("u")}, but {@code #{u.addr.city}} is two under both
         * readings, so the scan never has to guess which one applies.
         */
        boolean deeperThanOneProperty() {
            return path.split("\\.", -1).length >= 3;
        }

        /** The inline handler class, or null. */
        String typeHandler() {
            for (String option : options) {
                int equals = option.indexOf('=');
                if (equals > 0 && TYPE_HANDLER_OPTION.equals(option.substring(0, equals).trim())) {
                    return option.substring(equals + 1).trim();
                }
            }
            return null;
        }
    }

    static List<Placeholder> in(SourceText source) {
        List<Placeholder> found = new ArrayList<>();
        for (SourceText.Match match : source.matches(HASH)) {
            String body = match.group() == null ? "" : match.group();
            String[] parts = body.split(",");
            String path = parts[0].trim();
            if (path.isEmpty() || !PROPERTY_PATH.matcher(path).matches()) {
                continue; // #{} on its own, or SQL text assembled in Java
            }
            List<String> options = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                options.add(parts[i].trim());
            }
            found.add(new Placeholder(match.line(), match.text(), path, List.copyOf(options)));
        }
        return found;
    }
}
