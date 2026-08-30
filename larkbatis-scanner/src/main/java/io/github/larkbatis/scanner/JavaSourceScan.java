package io.github.larkbatis.scanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Java half of a migration: annotations, method signatures and direct
 * {@code SqlSession} calls.
 *
 * <p>This scan is <b>textual</b>, and the report says so. Running javac would
 * mean the tool could only be pointed at a project that already builds with
 * its own dependencies resolved — which is the opposite of what someone
 * evaluating a migration wants. The cost is that a signature spanning several
 * lines can be missed; the counts are a floor, not a total.
 *
 * <p>Only files that mention {@code org.apache.ibatis} are read past their
 * imports, so an ordinary service class cannot contribute a false positive.
 */
public final class JavaSourceScan {

    private static final Pattern MYBATIS_IMPORT = Pattern.compile("org\\.apache\\.ibatis");

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern PROVIDER =
            Pattern.compile("@(Select|Insert|Update|Delete)Provider\\b");
    private static final Pattern INTERCEPTOR =
            Pattern.compile("@Intercepts\\b|implements\\s+Interceptor\\b");
    private static final Pattern ROW_BOUNDS = Pattern.compile("\\bRowBounds\\b");
    private static final Pattern CACHE_NAMESPACE = Pattern.compile("@CacheNamespace(Ref)?\\b");
    private static final Pattern NESTED_SELECT =
            Pattern.compile("@(One|Many)\\s*\\(\\s*select\\s*=");
    private static final Pattern DISCRIMINATOR = Pattern.compile("@TypeDiscriminator\\b");
    private static final Pattern SCRIPT = Pattern.compile("<script>");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^}]*?)\\s*}");
    private static final Pattern SESSION_CALL = Pattern.compile(
            "\\.(selectList|selectOne|selectMap|selectCursor)\\s*\\(");
    private static final Pattern LANG_DRIVER = Pattern.compile("@Lang\\b");

    /** An interface method declaration on one line, with its parameter list captured. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:public\\s+|default\\s+|static\\s+)*"
                    + "[\\w.$]+(?:\\s*<[^;]*>)?(?:\\s*\\[\\s*])*\\s+\\w+\\s*\\(([^)]*)\\)\\s*;");

    private static final Pattern MAP_PARAMETER = Pattern.compile(
            "(?:^|[\\s<(,])(?:java\\.util\\.)?(Map|HashMap|LinkedHashMap|TreeMap)\\s*[<\\s]"
                    + "|(?:^|[\\s(,])(?:java\\.lang\\.)?Object\\s+\\w+\\s*(?:,|$)");

    /** The same declaration, captured by name instead of by parameter list. */
    private static final Pattern METHOD_NAME = Pattern.compile(
            "^\\s*(?:public\\s+|default\\s+|static\\s+)*"
                    + "[\\w.$]+(?:\\s*<[^;]*>)?(?:\\s*\\[\\s*])*\\s+(\\w+)\\s*\\([^)]*\\)\\s*;");

    private static final Pattern INTERFACE_EXTENDS = Pattern.compile(
            "\\binterface\\s+\\w+\\s*(?:<[^>]*>\\s*)?extends\\s+([\\w.$]+)");

    /**
     * Distinguishes a mapper from the rest of a MyBatis-aware file set — a
     * config class, a handler, a service holding a SqlSession. The two rules
     * below are about the shape of a <em>mapper interface</em>, and reporting
     * them on anything else would be noise at BLOCKER level.
     */
    private static final Pattern MAPPER_MARKER =
            Pattern.compile("@(Mapper|Select|Insert|Update|Delete)\\b");

    private JavaSourceScan() {
    }

    public static List<Finding> scan(Path file) throws IOException {
        return scan(SourceText.ofJava(file));
    }

    /**
     * The same scan over an already-read file. {@link MigrationScan} reads
     * every Java source once and needs the text again afterwards to work out
     * the file's fully-qualified name, so re-reading here would be a second
     * pass over the whole tree for nothing.
     */
    public static List<Finding> scan(SourceText source) {
        Path file = source.file();
        if (source.matches(MYBATIS_IMPORT).isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        simple(source, PROVIDER, Rule.PROVIDER_ANNOTATION, findings);
        simple(source, INTERCEPTOR, Rule.PLUGIN, findings);
        simple(source, ROW_BOUNDS, Rule.ROW_BOUNDS, findings);
        simple(source, CACHE_NAMESPACE, Rule.SECOND_LEVEL_CACHE, findings);
        simple(source, NESTED_SELECT, Rule.NESTED_SELECT, findings);
        simple(source, DISCRIMINATOR, Rule.DISCRIMINATOR, findings);
        simple(source, SCRIPT, Rule.SCRIPT_ANNOTATION, findings);
        simple(source, LANG_DRIVER, Rule.PROVIDER_ANNOTATION, findings);
        simple(source, SESSION_CALL, Rule.SQL_SESSION_CALL, findings);

        for (SourceText.Match match : source.matches(DOLLAR)) {
            findings.add(new Finding(file, match.line(), Rule.DOLLAR_SPLICE, null,
                    "${" + (match.group() == null ? "" : match.group()) + "}"));
        }
        for (SourceText.Match match : source.matches(METHOD_DECLARATION)) {
            String parameters = match.group();
            if (parameters != null && !parameters.isBlank()
                    && MAP_PARAMETER.matcher(parameters).find()) {
                findings.add(new Finding(file, match.line(), Rule.MAP_PARAMETER, null,
                        parameters.strip()));
            }
        }
        for (BindPlaceholders.Placeholder bind : BindPlaceholders.in(source)) {
            if (bind.deeperThanOneProperty()) {
                findings.add(new Finding(file, bind.line(), Rule.DEEP_PROPERTY_PATH, null,
                        bind.text()));
            }
            if (bind.typeHandler() != null) {
                findings.add(new Finding(file, bind.line(), Rule.INLINE_TYPE_HANDLER, null,
                        bind.text()));
            }
        }
        if (!source.matches(MAPPER_MARKER).isEmpty()) {
            findings.addAll(overloads(source));
            for (SourceText.Match match : source.matches(INTERFACE_EXTENDS)) {
                findings.add(new Finding(file, match.line(), Rule.MAPPER_INHERITANCE, null,
                        match.text().strip()));
            }
        }
        return findings;
    }

    /**
     * Two abstract methods sharing a name. Reported on the second and every
     * later declaration, because the first one is the one that stays — which
     * is also the shape of the fix.
     */
    private static List<Finding> overloads(SourceText source) {
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SourceText.Match match : source.matches(METHOD_NAME)) {
            String name = match.group();
            if (name != null && !seen.add(name)) {
                findings.add(new Finding(source.file(), match.line(),
                        Rule.OVERLOADED_MAPPER_METHOD, null, name + "(...)"));
            }
        }
        return findings;
    }

    /**
     * The file's fully-qualified type name, taken from its package declaration
     * and its file name. Only the public top-level type of a well-named file
     * is reachable this way, which is exactly what a {@code resultType} names.
     */
    public static String fqnOf(SourceText source) {
        String simple = source.file().getFileName().toString().replaceFirst("\\.java$", "");
        for (SourceText.Match match : source.matches(PACKAGE_DECLARATION)) {
            return match.group() + "." + simple;
        }
        return simple; // default package
    }

    private static void simple(SourceText source, Pattern pattern, Rule rule,
            List<Finding> findings) {
        for (SourceText.Match match : source.matches(pattern)) {
            if (isDeclaration(source.line(match.line()))) {
                continue;
            }
            findings.add(new Finding(source.file(), match.line(), rule, null,
                    match.text().strip()));
        }
    }

    /**
     * An {@code import org.apache.ibatis.session.RowBounds;} is not a use of
     * RowBounds, and counting it would double every finding in the file.
     */
    private static boolean isDeclaration(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("import ") || trimmed.startsWith("package ");
    }
}
