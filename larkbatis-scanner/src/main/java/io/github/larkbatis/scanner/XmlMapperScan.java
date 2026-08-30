package io.github.larkbatis.scanner;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.expr.ExprCompiler;
import io.github.larkbatis.processor.frontend.xml.MapperXmlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads one mapper XML twice, on purpose.
 *
 * <p>The streaming walk itemises every construct with a line number and keeps
 * going past the first problem — which is what a migration report needs, since
 * one {@code <bind>} must not hide the other forty findings in the file.
 *
 * <p>Then {@link MapperXmlParser} — the frontend the processor actually runs —
 * gets the same file, and its verdict decides whether the file would load at
 * all. Its diagnostic is only reported when the walk found nothing that
 * explains the rejection; otherwise the itemised findings say it better.
 */
public final class XmlMapperScan {

    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{\\s*([^}]*?)\\s*}");
    private static final Pattern SELECT_KEYWORD = Pattern.compile("(?i)\\bselect\\b");
    private static final Pattern FROM_KEYWORD = Pattern.compile("(?i)\\bfrom\\b");

    private final Path file;
    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> resultClasses = new LinkedHashSet<>();
    private final List<StatementRange> statements = new ArrayList<>();

    private XmlMapperScan(Path file) {
        this.file = file;
    }

    /** @return null when the file is not a mapper (config files, DTD neighbours, …) */
    public static XmlMapperScan scan(Path file) throws IOException {
        XmlMapperScan scan = new XmlMapperScan(file);
        if (!scan.walk()) {
            return null;
        }
        SourceText source = SourceText.ofXml(file);
        scan.scanDollarSplices(source);
        scan.scanBindPlaceholders(source);
        scan.confirmWithFrontend();
        return scan;
    }

    public List<Finding> findings() {
        return findings;
    }

    public List<StatementRange> statements() {
        return statements;
    }

    /**
     * Every class this mapper names as a result: {@code resultType},
     * {@code <resultMap type>}, {@code javaType} and {@code ofType}. The
     * property types inside those classes have to satisfy the whitelist, and
     * this is the only place the scan learns which classes to look at — a
     * result class is an ordinary POJO that mentions MyBatis nowhere.
     */
    public Set<String> resultClasses() {
        return Set.copyOf(resultClasses);
    }

    // --- pass 1: the streaming walk -------------------------------------------

    private boolean walk() throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // A mapper DTD reference must never turn a scan into an HTTP request.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            return walk(reader);
        } catch (XMLStreamException e) {
            // Not well-formed, or not XML at all. A corpus always has some;
            // the file simply is not evidence about anything.
            return false;
        }
    }

    private boolean walk(XMLStreamReader reader) throws XMLStreamException {
        boolean isMapper = false;
        Deque<String> open = new ArrayDeque<>();
        Deque<Integer> statementStart = new ArrayDeque<>();
        Deque<String> statementId = new ArrayDeque<>();
        String current = null;
        int nestedResultDepth = 0;

        while (reader.hasNext()) {
            int event = reader.next();
            int line = reader.getLocation().getLineNumber();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                if (open.isEmpty()) {
                    if (!"mapper".equals(name)) {
                        return false; // mybatis-config.xml, logback.xml, anything else
                    }
                    isMapper = true;
                }
                open.push(name);
                if (STATEMENTS.contains(name)) {
                    current = attribute(reader, "id");
                    statementId.push(current == null ? "?" : current);
                    statementStart.push(line);
                }
                if (isNestedResult(name)) {
                    nestedResultDepth++;
                }
                inspect(reader, name, line, current, nestedResultDepth);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName();
                open.pop();
                if (isNestedResult(name)) {
                    nestedResultDepth--;
                }
                if (STATEMENTS.contains(name)) {
                    statements.add(new StatementRange(statementId.pop(), name,
                            statementStart.pop(), line));
                    current = statementId.peek();
                }
            }
        }
        return isMapper;
    }

    private static boolean isNestedResult(String name) {
        return "association".equals(name) || "collection".equals(name) || "case".equals(name);
    }

    private void inspect(XMLStreamReader reader, String name, int line, String statement,
            int nestedResultDepth) {
        switch (name) {
            case "bind" -> add(line, Rule.BIND, statement, "<bind name=\""
                    + nullSafe(attribute(reader, "name")) + "\">");
            case "discriminator" -> add(line, Rule.DISCRIMINATOR, statement,
                    "<discriminator column=\"" + nullSafe(attribute(reader, "column")) + "\">");
            case "constructor" -> add(line, Rule.CONSTRUCTOR_RESULT, statement, "<constructor>");
            case "parameterMap" -> add(line, Rule.PARAMETER_MAP, statement, "<parameterMap>");
            case "cache", "cache-ref" -> add(line, Rule.SECOND_LEVEL_CACHE, statement, "<" + name + ">");
            case "selectKey" -> add(line, Rule.SELECT_KEY, statement,
                    "<selectKey keyProperty=\"" + nullSafe(attribute(reader, "keyProperty")) + "\">");
            case "foreach" -> add(line, Rule.FOREACH, statement,
                    "collection=\"" + nullSafe(attribute(reader, "collection")) + "\"");
            case "if", "when" -> checkTest(reader, line, statement);
            case "include" -> checkInclude(reader, line, statement);
            case "resultMap" -> checkResultMap(reader, line);
            case "association", "collection" -> checkNestedResult(reader, name, line,
                    nestedResultDepth);
            default -> { }
        }
        if (STATEMENTS.contains(name)) {
            checkStatementAttributes(reader, line, statement);
        }
        collectResultClasses(reader, name);
        checkUnqualifiedTypes(reader, name, line, statement);
        // Attributes that can appear on almost any element, so they are
        // checked here rather than in one element's own case. fetchType in
        // particular belongs to <association>/<collection>, never a statement.
        String typeHandler = attribute(reader, "typeHandler");
        if (typeHandler != null) {
            add(line, Rule.TYPE_HANDLER, statement, "typeHandler=\"" + typeHandler + "\"");
        }
        if ("lazy".equalsIgnoreCase(attribute(reader, "fetchType"))) {
            add(line, Rule.LAZY_LOADING, statement, "<" + name + " fetchType=\"lazy\">");
        }
    }

    private void checkTest(XMLStreamReader reader, int line, String statement) {
        String test = attribute(reader, "test");
        if (test == null) {
            return;
        }
        try {
            ExprCompiler.GrammarCheck check = ExprCompiler.checkGrammar(test);
            if (check.barePath()) {
                add(line, Rule.EXPRESSION_BARE_PATH, statement, "test=\"" + test + "\"");
            }
            // checkGrammar is syntax only — it has no types and so accepts any
            // call. The typed pass is where trim() is refused, and a scan that
            // stayed silent about it would report "compiles as-is" for the most
            // common <if test> idiom MyBatis has.
            for (String call : check.valueCalls()) {
                add(line, Rule.EXPRESSION_VALUE_CALL, statement,
                        "test=\"" + test + "\" — " + call + "()");
            }
            for (String call : check.untypedCalls()) {
                add(line, Rule.EXPRESSION_UNTYPED_CALL, statement,
                        "test=\"" + test + "\" — " + call + "()");
            }
        } catch (LarkBatisProcessingException e) {
            add(line, Rule.EXPRESSION_OUTSIDE_GRAMMAR, statement,
                    "test=\"" + test + "\" — " + e.getMessage());
        } catch (RuntimeException e) {
            add(line, Rule.EXPRESSION_OUTSIDE_GRAMMAR, statement,
                    "test=\"" + test + "\" — the parser could not read this");
        }
    }

    private void checkInclude(XMLStreamReader reader, int line, String statement) {
        String refid = attribute(reader, "refid");
        if (refid != null && refid.contains("${")) {
            add(line, Rule.DYNAMIC_INCLUDE, statement, "refid=\"" + refid + "\"");
        }
    }

    private void checkResultMap(XMLStreamReader reader, int line) {
        String extend = attribute(reader, "extends");
        if (extend != null) {
            add(line, Rule.RESULT_MAP_EXTENDS, null,
                    "<resultMap id=\"" + nullSafe(attribute(reader, "id"))
                            + "\" extends=\"" + extend + "\">");
        }
    }

    private void checkNestedResult(XMLStreamReader reader, String name, int line, int depth) {
        String select = attribute(reader, "select");
        if (select != null) {
            add(line, Rule.NESTED_SELECT, null, "<" + name + " select=\"" + select + "\">");
        }
        if (depth > 1) {
            add(line, Rule.RESULT_MAP_DEPTH, null,
                    "<" + name + "> nested " + depth + " levels deep");
        }
    }

    private void checkStatementAttributes(XMLStreamReader reader, int line, String statement) {
        String parameterType = attribute(reader, "parameterType");
        if (parameterType != null && isMapLike(parameterType)) {
            add(line, Rule.MAP_PARAMETER, statement, "parameterType=\"" + parameterType + "\"");
        }
        // The same map-like test on the way out. A statement returning a
        // HashMap per row is a blocker for the same reason as one taking one:
        // there is no type to generate against.
        String resultType = attribute(reader, "resultType");
        if (resultType != null && isMapLike(resultType)) {
            add(line, Rule.MAP_RESULT, statement, "resultType=\"" + resultType + "\"");
        }
        String statementType = attribute(reader, "statementType");
        if (statementType != null && !"PREPARED".equalsIgnoreCase(statementType)) {
            add(line, Rule.STATEMENT_TYPE, statement, "statementType=\"" + statementType + "\"");
        }
    }

    /**
     * Notes every class named as a result, on any element that can name one.
     * Only fully-qualified names are kept: a MyBatis {@code typeAlias} is
     * resolved from {@code mybatis-config.xml} or a package scan, and guessing
     * which class {@code resultType="user"} means would put wrong file names in
     * the report — worse than the silence of leaving it out.
     */
    private void collectResultClasses(XMLStreamReader reader, String name) {
        String declared = switch (name) {
            case "select" -> attribute(reader, "resultType");
            case "resultMap" -> attribute(reader, "type");
            case "association" -> attribute(reader, "javaType");
            case "collection" -> attribute(reader, "ofType");
            default -> null;
        };
        if (declared == null) {
            return;
        }
        String fqn = declared.trim();
        if (fqn.indexOf('.') > 0 && !isMapLike(fqn) && !fqn.startsWith("java.")) {
            resultClasses.add(fqn);
        }
    }

    /**
     * Counts the type names that are not fully qualified. Resolving them is
     * what this scan refuses to do; counting them is what it owes the reader,
     * because a codebase that aliases its whole model package has an edit in
     * every statement and would otherwise scan clean.
     *
     * <p>{@code parameterType} is read here too, even though
     * {@link #collectResultClasses} has no use for it: an alias costs the same
     * edit whichever attribute carries it.
     */
    private void checkUnqualifiedTypes(XMLStreamReader reader, String name, int line,
            String statement) {
        for (String attribute : TYPE_ATTRIBUTES) {
            String declared = attribute(reader, attribute);
            if (declared == null) {
                continue;
            }
            String type = declared.trim();
            if (type.isEmpty() || type.indexOf('.') > 0 || isMapLike(type)
                    || BUILT_IN_ALIASES.contains(type.toLowerCase(Locale.ROOT))) {
                continue;
            }
            add(line, Rule.UNQUALIFIED_TYPE_NAME, statement,
                    attribute + "=\"" + type + "\"");
        }
    }

    /** Every attribute in mapper XML that names a Java type. */
    private static final List<String> TYPE_ATTRIBUTES =
            List.of("resultType", "parameterType", "type", "javaType", "ofType");

    /**
     * MyBatis's own aliases for JDK types. They are unqualified too, and
     * {@code resultType="long"} on a count query is not a migration cost —
     * LarkBatis reads the return type off the mapper method instead.
     */
    private static final Set<String> BUILT_IN_ALIASES = Set.of(
            "string", "byte", "short", "int", "integer", "long", "float", "double",
            "boolean", "date", "decimal", "bigdecimal", "biginteger", "object",
            "_byte", "_short", "_int", "_integer", "_long", "_float", "_double",
            "_boolean", "byte[]", "_byte[]");

    static boolean isMapLike(String type) {
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("map")
                || normalized.equals("hashmap")
                || normalized.equals("object")
                || normalized.endsWith("java.util.map")
                || normalized.endsWith("java.util.hashmap")
                || normalized.endsWith("java.lang.object");
    }

    // --- pass 2: ${} positions -------------------------------------------------

    private void scanDollarSplices(SourceText source) {
        for (SourceText.Match match : source.matches(DOLLAR)) {
            StatementRange statement = statementAt(match.line());
            String id = statement == null ? null : statement.id();
            add(match.line(), Rule.DOLLAR_SPLICE, id, "${" + nullSafe(match.group()) + "}");
            if (statement != null && "select".equals(statement.kind())
                    && inSelectList(source, statement, match)) {
                add(match.line(), Rule.DOLLAR_IN_SELECT_LIST, id,
                        "${" + nullSafe(match.group()) + "}");
            }
        }
    }

    /**
     * The two things a {@code #{}} can carry that the generator will not take:
     * a path more than one property deep, and an inline {@code typeHandler=}.
     * Both live in SQL text rather than in an element, which is why the
     * streaming walk cannot see them.
     */
    private void scanBindPlaceholders(SourceText source) {
        for (BindPlaceholders.Placeholder bind : BindPlaceholders.in(source)) {
            StatementRange statement = statementAt(bind.line());
            String id = statement == null ? null : statement.id();
            if (bind.deeperThanOneProperty()) {
                add(bind.line(), Rule.DEEP_PROPERTY_PATH, id, bind.text());
            }
            String handler = bind.typeHandler();
            if (handler != null) {
                add(bind.line(), Rule.INLINE_TYPE_HANDLER, id, bind.text());
            }
        }
    }

    /**
     * Whether the line sits between a SELECT and its FROM. A heuristic, and
     * said to be one in the report: the alternative is parsing SQL, and the
     * consequence of being wrong is one advisory line, not a wrong build.
     */
    private boolean inSelectList(SourceText source, StatementRange statement,
            SourceText.Match match) {
        StringBuilder before = new StringBuilder();
        for (int i = statement.startLine(); i < match.line(); i++) {
            before.append(source.line(i)).append('\n');
        }
        // only what precedes the splice on its own line: in
        // `SELECT ${columns} FROM users` the FROM comes after, and taking the
        // whole line would decide the splice was not in the select list.
        before.append(source.line(match.line()), 0, match.column());
        String text = before.toString();
        var select = SELECT_KEYWORD.matcher(text);
        int lastSelect = -1;
        while (select.find()) {
            lastSelect = select.start();
        }
        if (lastSelect < 0) {
            return false;
        }
        return !FROM_KEYWORD.matcher(text.substring(lastSelect)).find();
    }

    private StatementRange statementAt(int line) {
        return statements.stream().filter(s -> s.contains(line)).findFirst().orElse(null);
    }

    // --- pass 3: what the real frontend says ------------------------------------

    private void confirmWithFrontend() {
        try {
            MapperXmlParser.parseIfMapper(file);
        } catch (MapperXmlParser.NotWellFormedException e) {
            // pass 1 would already have refused this file
        } catch (LarkBatisProcessingException e) {
            if (findings.stream().noneMatch(f -> f.severity() == Severity.BLOCKER)) {
                add(0, Rule.PARSE_REJECTED, null, e.getMessage());
            }
        } catch (RuntimeException e) {
            add(0, Rule.PARSE_REJECTED, null,
                    "the frontend failed on this file, which is a LarkBatis bug: " + e);
        }
    }

    private void add(int line, Rule rule, String statement, String detail) {
        findings.add(new Finding(file, line, rule, statement, detail));
    }

    private static String attribute(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
