package io.github.larkbatis.processor.frontend.xml;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.dyn.DynNode;
import io.github.larkbatis.processor.ir.StatementKind;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses one mapper XML file into per-statement {@link DynNode} trees. This
 * is the build-time counterpart of XMLMapperBuilder + XMLScriptBuilder, minus
 * everything LarkBatis drops: {@code <bind>},
 * {@code <selectKey>}, caches and providers are all compile errors with the
 * replacement spelled out. {@code <resultMap>} is kept but narrowed to one
 * level of {@code <association>}/{@code <collection>} over a join.
 *
 * <p>{@code <sql>}/{@code <include>} are inlined here (static {@code refid},
 * literal {@code <property>} values only); the DTD is never
 * fetched, entity expansion is off.
 */
public final class MapperXmlParser {

    /** One parsed mapper file: namespace plus its statements and result maps by id. */
    public record XmlMapper(String namespace, Path file, Map<String, XmlStatement> statements,
                            Map<String, XmlResultMap> resultMaps) {
    }

    /**
     * One parsed statement, still untyped: typing happens when it is paired
     * with the interface method of the same name.
     */
    public record XmlStatement(String id, StatementKind kind, List<DynNode> nodes,
                               String resultType, String resultMap, boolean useGeneratedKeys,
                               String keyProperty, String keyColumn) {
    }

    /**
     * One {@code <resultMap>}: an explicit column-to-property mapping, with at
     * most one level of nesting. Still untyped — the
     * declared type names are resolved against the compilation when the map is
     * paired with a statement.
     */
    public record XmlResultMap(String id, String type, List<XmlMapping> mappings,
                               XmlNested nested) {
    }

    /**
     * One {@code <id>} or {@code <result>}. {@code id} marks the columns the
     * grouping loop compares, which is what makes the join collapse back into
     * objects.
     */
    public record XmlMapping(String property, String column, boolean id, String handler) {
    }

    /**
     * The single nested {@code <association>} or {@code <collection>}. A
     * second level would need a second grouping key per row and a second
     * ordering guarantee from the query; one level is the deliberate limit.
     */
    public record XmlNested(boolean collection, String property, String type,
                            List<XmlMapping> mappings) {
    }

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    /** Statement attributes LarkBatis understands; anything else is an error. */
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "id", "resultType", "resultMap", "parameterType",
            "useGeneratedKeys", "keyProperty", "keyColumn");

    /** Attributes of {@code <id>} and {@code <result>} inside a {@code <resultMap>}. */
    private static final Set<String> ALLOWED_MAPPING_ATTRIBUTES =
            Set.of("property", "column", "typeHandler");

    /**
     * Attributes of a nested {@code <association>} or {@code <collection>}.
     * {@code select}, {@code resultMap} and {@code columnPrefix} are not here:
     * each is refused earlier with a reason of its own, so reaching this set
     * means the attribute is one nobody has a story for. {@code typeHandler}
     * and {@code column} are here only so the generic message does not
     * pre-empt the specific one below.
     */
    private static final Set<String> ALLOWED_NESTED_ATTRIBUTES = Set.of(
            "property", "javaType", "ofType", "typeHandler", "column");

    private final Path file;
    private final Map<String, Element> sqlFragments = new LinkedHashMap<>();
    private final Map<String, XmlResultMap> resultMaps = new LinkedHashMap<>();

    private MapperXmlParser(Path file) {
        this.file = file;
    }

    public static XmlMapper parse(Path file) {
        XmlMapper mapper = parseIfMapper(file);
        if (mapper == null) {
            throw new LarkBatisProcessingException(null,
                    file + ": the root element must be <mapper namespace=\"...\">");
        }
        return mapper;
    }

    /**
     * Parses the file when its root element is {@code <mapper>}, returns null
     * otherwise — the directory scan must step over unrelated XML (logback
     * configs and friends) instead of failing the build. A file that IS a
     * mapper still reports all its problems as errors.
     */
    public static XmlMapper parseIfMapper(Path file) {
        Document document;
        try {
            String content = Files.readString(file);
            document = newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (IOException | SAXException e) {
            throw new NotWellFormedException(
                    file + " is not well-formed XML and was ignored (" + e.getMessage()
                            + ") — if this was meant to be a mapper file, fix it");
        }
        Element root = document.getDocumentElement();
        if (root == null || !root.getTagName().equals("mapper")) {
            return null;
        }
        return new MapperXmlParser(file).parseDocument(document);
    }

    /** Unparseable XML found by the directory scan: a warning, not an error. */
    public static final class NotWellFormedException extends RuntimeException {
        NotWellFormedException(String message) {
            super(message);
        }
    }

    /** Offline and entity-safe: the MyBatis DTD reference is never fetched. */
    private static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setValidating(false);
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private XmlMapper parseDocument(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !root.getTagName().equals("mapper")) {
            throw error("the root element must be <mapper namespace=\"...\">");
        }
        String namespace = root.getAttribute("namespace");
        if (namespace.isEmpty()) {
            throw error("<mapper> needs a namespace: the mapper interface's fully-qualified name");
        }

        // collect <sql> fragments first: an <include> may point forward
        for (Element child : childElements(root)) {
            if (child.getTagName().equals("sql")) {
                String id = child.getAttribute("id");
                if (id.isEmpty()) {
                    throw error("<sql> needs an id");
                }
                if (sqlFragments.put(id, child) != null) {
                    throw error("duplicate <sql id=\"" + id + "\">");
                }
            }
        }

        // <resultMap> before the statements, for the same reason as <sql>:
        // a statement may reference a map declared further down the file
        for (Element child : childElements(root)) {
            if (child.getTagName().equals("resultMap")) {
                XmlResultMap resultMap = parseResultMap(child);
                if (resultMaps.put(resultMap.id(), resultMap) != null) {
                    throw error("duplicate <resultMap id=\"" + resultMap.id() + "\">");
                }
            }
        }

        Map<String, XmlStatement> statements = new LinkedHashMap<>();
        for (Element child : childElements(root)) {
            String tag = child.getTagName();
            if (tag.equals("sql") || tag.equals("resultMap")) {
                continue;
            }
            if (!STATEMENT_TAGS.contains(tag)) {
                throw error(unsupportedTopLevel(tag));
            }
            XmlStatement statement = parseStatement(child);
            if (statement.resultMap() != null && !resultMaps.containsKey(statement.resultMap())) {
                // MyBatis resolves dotted ids across mapper files
                // (applyCurrentNamespace); LarkBatis narrowed <resultMap> to
                // its own file, the same way it narrowed <include>
                throw error(statement.id() + ": no <resultMap id=\"" + statement.resultMap()
                        + "\"> in this mapper file"
                        + (statement.resultMap().indexOf('.') < 0 ? ""
                                : " — a cross-mapper resultMap reference was narrowed away"
                                        + "; declare the map here"));
            }
            if (statements.put(statement.id(), statement) != null) {
                throw error("duplicate statement id \"" + statement.id() + "\"");
            }
        }
        return new XmlMapper(namespace, file, Map.copyOf(statements), Map.copyOf(resultMaps));
    }

    private String unsupportedTopLevel(String tag) {
        return switch (tag) {
            case "cache", "cache-ref" -> "<" + tag + "> was dropped: cache at the service layer,"
                    + " where invalidation is yours";
            case "parameterMap" -> "<parameterMap> was dropped with Object/Map parameters";
            default -> "unknown element <" + tag + "> in mapper XML";
        };
    }

    private XmlStatement parseStatement(Element element) {
        String id = element.getAttribute("id");
        if (id.isEmpty()) {
            throw error("<" + element.getTagName() + "> needs an id (the mapper method name)");
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            String name = attributes.item(i).getNodeName();
            if (!ALLOWED_ATTRIBUTES.contains(name)) {
                throw error(id + ": attribute \"" + name + "\" is not supported; "
                        + unsupportedAttribute(name));
            }
        }
        StatementKind kind = StatementKind.valueOf(
                element.getTagName().toUpperCase(java.util.Locale.ENGLISH));
        if (kind != StatementKind.INSERT && !element.getAttribute("useGeneratedKeys").isEmpty()) {
            throw error(id + ": useGeneratedKeys only applies to <insert>");
        }
        String resultType = emptyToNull(element.getAttribute("resultType"));
        String resultMap = emptyToNull(element.getAttribute("resultMap"));
        if (resultType != null && resultMap != null) {
            throw error(id + ": resultType and resultMap are alternatives, not a pair");
        }
        if (resultMap != null && kind != StatementKind.SELECT) {
            throw error(id + ": resultMap only applies to <select>");
        }
        return new XmlStatement(id, kind,
                parseChildren(element, Map.of(), new ArrayList<>()),
                resultType, resultMap,
                element.getAttribute("useGeneratedKeys").equals("true"),
                element.getAttribute("keyProperty"),
                element.getAttribute("keyColumn"));
    }

    private String unsupportedAttribute(String name) {
        return switch (name) {
            case "statementType" -> "only PREPARED statements exist in LarkBatis";
            case "flushCache", "useCache" -> "there is no statement cache to control";
            case "databaseId" -> "per-database statement variants are not supported;"
                    + " give each database its own mapper interface";
            case "timeout", "fetchSize", "resultSetType" -> "per-statement JDBC tuning is"
                    + " not supported; set it on the DataSource or the driver";
            default -> "it has no LarkBatis equivalent";
        };
    }

    // --- <resultMap> ---------------------------------------------------------------

    /**
     * One {@code <resultMap>}, narrowed to what a generator can turn into a
     * loop: explicit column-to-property mappings, and at
     * most one {@code <association>} or {@code <collection>} filled from the
     * same join. Everything else in MyBatis's result-map vocabulary is a
     * compile error with the replacement spelled out — a result map that
     * silently ignores half of what it was given is worse than one that
     * refuses.
     */
    private XmlResultMap parseResultMap(Element element) {
        String id = element.getAttribute("id");
        if (id.isEmpty()) {
            throw error("<resultMap> needs an id");
        }
        if (!element.getAttribute("extends").isEmpty()) {
            throw error("<resultMap id=\"" + id + "\" extends=\"...\">: result-map inheritance"
                    + " was narrowed away — spell the mappings out here");
        }
        if (!element.getAttribute("autoMapping").isEmpty()) {
            throw error("<resultMap id=\"" + id + "\">: autoMapping has no LarkBatis"
                    + " equivalent — a result map maps exactly what it declares, and a"
                    + " statement that wants name matching uses resultType instead");
        }
        String type = element.getAttribute("type");
        if (type.isEmpty()) {
            throw error("<resultMap id=\"" + id + "\"> needs a type");
        }

        List<XmlMapping> mappings = new ArrayList<>();
        XmlNested nested = null;
        for (Element child : childElements(element)) {
            switch (child.getTagName()) {
                case "id", "result" -> mappings.add(parseMapping(id, child));
                case "association", "collection" -> {
                    if (nested != null) {
                        throw error("<resultMap id=\"" + id + "\"> has more than one nested"
                                + " mapping; one level, one join, one grouping key"
                                + "");
                    }
                    nested = parseNested(id, child);
                }
                case "constructor" -> throw error("<resultMap id=\"" + id + "\">:"
                        + " <constructor> was dropped — result classes are built with a no-arg"
                        + " constructor and setters");
                case "discriminator" -> throw error("<resultMap id=\"" + id + "\">:"
                        + " <discriminator> was dropped — it picks the"
                        + " shape of the result from a value, which is the one thing a"
                        + " build-time generator cannot do");
                default -> throw error("<resultMap id=\"" + id + "\">: unknown element <"
                        + child.getTagName() + ">");
            }
        }
        if (mappings.isEmpty()) {
            throw error("<resultMap id=\"" + id + "\"> maps no columns");
        }
        if (nested != null && mappings.stream().noneMatch(XmlMapping::id)) {
            // without a key there is nothing to group by, and every row would
            // start a new parent — the join would silently multiply the results
            throw error("<resultMap id=\"" + id + "\"> has a nested <"
                    + (nested.collection() ? "collection" : "association")
                    + "> but no <id>: the generated loop groups rows by the parent key,"
                    + " so at least one <id property=\"...\" column=\"...\"> is required"
                    + " and the query must ORDER BY that column");
        }
        return new XmlResultMap(id, type, List.copyOf(mappings), nested);
    }

    private XmlNested parseNested(String resultMapId, Element element) {
        boolean collection = element.getTagName().equals("collection");
        String where = "<resultMap id=\"" + resultMapId + "\"> <" + element.getTagName() + ">";
        String property = element.getAttribute("property");
        if (property.isEmpty()) {
            throw error(where + " needs a property");
        }
        if (!element.getAttribute("select").isEmpty()) {
            throw error(where + ": a nested select was dropped — it is one"
                    + " query per parent row, which is the N+1 the join is here to avoid."
                    + " Write the join and map it with this element's own <id>/<result>");
        }
        if (!element.getAttribute("resultMap").isEmpty()) {
            throw error(where + ": referencing another <resultMap> from a nested mapping was"
                    + " narrowed away — spell the child's <id>/<result> out here, which also"
                    + " keeps the one-level limit visible in the file");
        }
        if (!element.getAttribute("columnPrefix").isEmpty()) {
            throw error(where + ": columnPrefix was narrowed away — alias the child columns in"
                    + " the select list instead (SELECT i.id AS item_id, ...) so the column"
                    + " names in this file are the names the driver reports");
        }
        // Before this, an attribute nobody read was accepted in silence, so a
        // typeHandler here looked like it had been honoured.
        NamedNodeMap nestedAttributes = element.getAttributes();
        for (int i = 0; i < nestedAttributes.getLength(); i++) {
            String name = nestedAttributes.item(i).getNodeName();
            if (!ALLOWED_NESTED_ATTRIBUTES.contains(name)) {
                throw error(where + ": attribute \"" + name + "\" is not supported; "
                        + unsupportedMappingAttribute(name));
            }
        }
        if (!element.getAttribute("typeHandler").isEmpty()) {
            // MyBatis reaches a handler on this element only through a nested
            // select, which is dropped; the child of a join is built from its
            // own <id>/<result>, and those carry their own typeHandler
            throw error(where + ": typeHandler here has nothing to move — the child is built"
                    + " from its own <id>/<result>, so name the handler on the one that reads"
                    + " the column");
        }
        if (!element.getAttribute("column").isEmpty()) {
            throw error(where + ": column here feeds a nested select, which was dropped."
                    + " A join fills the child from its own <id>/<result>");
        }
        String type = collection ? element.getAttribute("ofType") : element.getAttribute("javaType");
        if (type.isEmpty()) {
            throw error(where + " needs " + (collection ? "ofType" : "javaType")
                    + ": the element type is resolved at build time, never guessed from a row");
        }

        List<XmlMapping> mappings = new ArrayList<>();
        for (Element child : childElements(element)) {
            switch (child.getTagName()) {
                case "id", "result" -> mappings.add(parseMapping(resultMapId, child));
                case "association", "collection" -> throw error(where + ": nesting stops at one"
                        + " level — a second level needs a second grouping"
                        + " key and a second ordering guarantee from the query");
                default -> throw error(where + ": unknown element <" + child.getTagName() + ">");
            }
        }
        if (mappings.isEmpty()) {
            throw error(where + " maps no columns");
        }
        if (mappings.stream().noneMatch(XmlMapping::id)) {
            // a LEFT JOIN with no match still produces a row, all child columns
            // NULL; the child <id> column is how the loop tells that apart from
            // a real child
            throw error(where + " needs an <id property=\"...\" column=\"...\">: its column"
                    + " being NULL is how a LEFT JOIN miss is recognised");
        }
        return new XmlNested(collection, property, type, List.copyOf(mappings));
    }

    private XmlMapping parseMapping(String resultMapId, Element element) {
        String where = "<resultMap id=\"" + resultMapId + "\"> <" + element.getTagName() + ">";
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            String name = attributes.item(i).getNodeName();
            if (!ALLOWED_MAPPING_ATTRIBUTES.contains(name)) {
                throw error(where + ": attribute \"" + name + "\" is not supported; "
                        + unsupportedMappingAttribute(name));
            }
        }
        String property = element.getAttribute("property");
        String column = element.getAttribute("column");
        if (property.isEmpty() && !column.isEmpty()) {
            // MyBatis allows <id column="x"/> as a grouping key that maps to no
            // property, comparing it through a CacheKey. LarkBatis compares the
            // parent key on a typed field instead, and a column with no property
            // has no type at build time.
            throw error(where + " has a column but no property: a grouping-only <id> was"
                    + " narrowed away — map the key to a property and"
                    + " mark that <id>, so the generated loop compares a typed field");
        }
        if (property.isEmpty() || column.isEmpty()) {
            throw error(where + " needs both property and column");
        }
        String handler = element.getAttribute("typeHandler").trim();
        if (element.hasAttribute("typeHandler") && handler.isEmpty()) {
            throw error(where + " has an empty typeHandler");
        }
        return new XmlMapping(property, column, element.getTagName().equals("id"),
                handler.isEmpty() ? null : handler);
    }

    private String unsupportedMappingAttribute(String name) {
        return switch (name) {
            case "javaType" -> "the property's type comes from its setter, which cannot disagree";
            case "jdbcType" -> "the JDBC type is chosen at build time from the property's type"
                    + "";
            case "select" -> "nested selects were dropped";
            case "notNullColumn", "columnPrefix", "resultMap", "foreignColumn", "fetchType",
                 "autoMapping" -> "it has no LarkBatis equivalent";
            default -> "it has no LarkBatis equivalent";
        };
    }

    // --- statement body → DynNode tree ---------------------------------------------

    private List<DynNode> parseChildren(Element parent, Map<String, String> variables,
            List<String> includeStack) {
        List<DynNode> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                out.add(new DynNode.Text(substitute(child.getNodeValue(), variables)));
                continue;
            }
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue; // comments, processing instructions
            }
            Element element = (Element) child;
            switch (element.getTagName()) {
                case "if" -> out.add(new DynNode.If(
                        requireAttribute(element, "test", variables),
                        parseChildren(element, variables, includeStack)));
                case "choose" -> out.add(parseChoose(element, variables, includeStack));
                case "where" -> out.add(DynNode.Trim.where(
                        parseChildren(element, variables, includeStack)));
                case "set" -> out.add(DynNode.Trim.set(
                        parseChildren(element, variables, includeStack)));
                case "trim" -> out.add(new DynNode.Trim(
                        emptyToNull(substitute(element.getAttribute("prefix"), variables)),
                        splitOverrides(substitute(element.getAttribute("prefixOverrides"), variables)),
                        emptyToNull(substitute(element.getAttribute("suffix"), variables)),
                        splitOverrides(substitute(element.getAttribute("suffixOverrides"), variables)),
                        parseChildren(element, variables, includeStack)));
                case "include" -> out.addAll(inlineInclude(element, variables, includeStack));
                case "foreach" -> out.add(parseForeach(element, variables, includeStack));
                case "bind" -> throw error("<bind> was dropped: compute the value"
                        + " in Java at the call site and pass it as a parameter"
                        + " (a LIKE pattern becomes \"%\" + term + \"%\" in the caller)");
                case "selectKey" -> throw error("<selectKey> was dropped;"
                        + " useGeneratedKeys covers auto-increment databases");
                default -> throw error("unknown element <" + element.getTagName()
                        + "> in SQL statement");
            }
        }
        return out;
    }

    private DynNode parseChoose(Element choose, Map<String, String> variables,
            List<String> includeStack) {
        List<DynNode.Choose.When> whens = new ArrayList<>();
        List<DynNode> otherwise = List.of();
        boolean sawOtherwise = false;
        for (Element child : childElements(choose)) {
            switch (child.getTagName()) {
                case "when" -> {
                    if (sawOtherwise) {
                        throw error("<otherwise> must be the last child of <choose>");
                    }
                    whens.add(new DynNode.Choose.When(
                            requireAttribute(child, "test", variables),
                            parseChildren(child, variables, includeStack)));
                }
                case "otherwise" -> {
                    if (sawOtherwise) {
                        throw error("too many <otherwise> in <choose>");
                    }
                    sawOtherwise = true;
                    otherwise = parseChildren(child, variables, includeStack);
                }
                default -> throw error("unknown element <" + child.getTagName()
                        + "> in <choose>: only <when> and <otherwise>");
            }
        }
        if (whens.isEmpty()) {
            throw error("<choose> needs at least one <when>");
        }
        return new DynNode.Choose(whens, otherwise);
    }

    /**
     * {@code <foreach>}. The body must always contribute text:
     * {@code ForEachSqlNode} applies the separator lazily, before the first
     * non-blank append of each iteration (mybatis-3
     * {@code ForEachSqlNode.apply} and its {@code PrefixedContext}), so an
     * iteration that
     * appends nothing consumes no separator. A statically non-blank body
     * makes {@code k > 0} exactly equivalent; a conditional one would not,
     * and generating subtly-wrong SQL is worse than refusing to generate.
     */
    private DynNode parseForeach(Element element, Map<String, String> variables,
            List<String> includeStack) {
        // nullable="false" is what LarkBatis does anyway (a null collection
        // is an error, MyBatis's own default); only nullable="true" asks for
        // behavior we do not have.
        if (element.getAttribute("nullable").equals("true")) {
            throw error("<foreach nullable=\"true\">: a null collection is always an error in"
                    + " LarkBatis. Wrap the loop in <if test=\"" + element.getAttribute("collection")
                    + " != null\"> to drop the fragment instead.");
        }
        String collection = requireAttribute(element, "collection", variables);
        List<DynNode> children = parseChildren(element, variables, includeStack);
        for (DynNode child : children) {
            if (child instanceof DynNode.If || child instanceof DynNode.Choose
                    || child instanceof DynNode.Trim) {
                throw error("<foreach collection=\"" + collection + "\"> body must always"
                        + " produce SQL: a conditional inside it changes where the separator"
                        + " lands. Move the condition outside the <foreach>.");
            }
        }
        if (children.stream().allMatch(child -> child instanceof DynNode.Text text
                && text.raw().isBlank())) {
            throw error("<foreach collection=\"" + collection + "\"> has an empty body");
        }
        return new DynNode.Foreach(collection,
                emptyToNull(substitute(element.getAttribute("item"), variables)),
                emptyToNull(substitute(element.getAttribute("index"), variables)),
                emptyToNull(substitute(element.getAttribute("open"), variables)),
                emptyToNull(substitute(element.getAttribute("separator"), variables)),
                emptyToNull(substitute(element.getAttribute("close"), variables)),
                children);
    }

    /** Inlines {@code <include refid>} at build time. */
    private List<DynNode> inlineInclude(Element include, Map<String, String> variables,
            List<String> includeStack) {
        String refid = substitute(include.getAttribute("refid"), variables);
        if (refid.isEmpty()) {
            throw error("<include> needs a refid");
        }
        if (refid.contains("${")) {
            throw error("<include refid=\"" + refid + "\">: dynamic refid was narrowed away —"
                    + " refid must be a static <sql> id");
        }
        Element fragment = sqlFragments.get(refid);
        if (fragment == null) {
            // The mybatis-3 corpus resolves dotted refids across mapper files
            // (applyCurrentNamespace); LarkBatis narrowed <include> to the
            // same file — the message says so instead of "no such id".
            if (refid.indexOf('.') >= 0) {
                throw error("<include refid=\"" + refid + "\">: cross-mapper <include> was"
                        + " narrowed away — every refid resolves within"
                        + " its own file; inline the fragment here, or share the SQL as a"
                        + " SqlFragment constant in Java");
            }
            throw error("<include refid=\"" + refid + "\">: no <sql id=\"" + refid
                    + "\"> in this mapper file");
        }
        if (includeStack.contains(refid)) {
            throw error("<include refid=\"" + refid + "\"> is circular: "
                    + String.join(" -> ", includeStack) + " -> " + refid);
        }

        Map<String, String> merged = new LinkedHashMap<>(variables);
        for (Element property : childElements(include)) {
            if (!property.getTagName().equals("property")) {
                throw error("<include> children must be <property> elements");
            }
            String name = property.getAttribute("name");
            String value = property.getAttribute("value");
            if (name.isEmpty()) {
                throw error("<include refid=\"" + refid + "\">: <property> needs a name");
            }
            if (value.contains("${")) {
                throw error("<include refid=\"" + refid + "\">: <property name=\"" + name
                        + "\"> must be a literal value");
            }
            merged.put(name, value);
        }

        includeStack.add(refid);
        List<DynNode> nodes = parseChildren(fragment, merged, includeStack);
        includeStack.remove(includeStack.size() - 1);
        return nodes;
    }

    /**
     * {@code ${name}} substitution inside an included fragment, from literal
     * {@code <property>} values. Unknown names stay untouched — they are
     * {@code ${}} splices for the statement compiler to vet.
     */
    private static String substitute(String text, Map<String, String> variables) {
        if (text == null || variables.isEmpty() || !text.contains("${")) {
            return text;
        }
        String out = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            out = out.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return out;
    }

    private String requireAttribute(Element element, String name, Map<String, String> variables) {
        String value = element.getAttribute(name);
        if (value.isEmpty()) {
            throw error("<" + element.getTagName() + "> needs a " + name + " attribute");
        }
        return substitute(value, variables);
    }

    /** TrimSqlNode.parseOverrides: a |-separated list, matched in order. */
    private static List<String> splitOverrides(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : attribute.split("\\|")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private LarkBatisProcessingException error(String message) {
        return new LarkBatisProcessingException(null, file.getFileName() + ": " + message);
    }
}
