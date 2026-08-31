package io.github.larkbatis.processor.frontend;

import io.github.larkbatis.annotations.Delete;
import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Options;
import io.github.larkbatis.annotations.OrderBy;
import io.github.larkbatis.annotations.PadPow2;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.annotations.Update;
import io.github.larkbatis.processor.frontend.dyn.DynNode;
import io.github.larkbatis.processor.frontend.dyn.DynamicLowering;
import io.github.larkbatis.processor.frontend.expr.ExprCompiler;
import io.github.larkbatis.processor.frontend.xml.MapperXmlParser;
import io.github.larkbatis.processor.ir.DynamicModel;
import io.github.larkbatis.processor.ir.KeyModel;
import io.github.larkbatis.processor.ir.MapperModel;
import io.github.larkbatis.processor.ir.NestedResult;
import io.github.larkbatis.processor.ir.ParamModel;
import io.github.larkbatis.processor.ir.ColumnNaming;
import io.github.larkbatis.processor.ir.PropertyModel;
import io.github.larkbatis.processor.ir.ReaderAccess;
import io.github.larkbatis.processor.ir.ResultModel;
import io.github.larkbatis.processor.ir.ReturnShape;
import io.github.larkbatis.processor.ir.SqlPiece;
import io.github.larkbatis.processor.ir.StatementKind;
import io.github.larkbatis.processor.ir.StatementModel;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

/**
 * Annotation frontend: mapper interface → {@link MapperModel} IR. The XML
 * frontend produces the same IR — one IR, two parsers.
 *
 * <p>All shape validation happens here; every rejection is a compile error on
 * the precise element. Emitters can assume a valid model.
 */
public final class AnnotationFrontend {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Identifiers the generated bodies use; loop variables must dodge them. */
    private static final Set<String> RESERVED_LOCALS =
            Set.of("s", "c", "ps", "rs", "gk", "n", "i", "out", "cols", "sql",
                    "parent", "nested", "ncols");

    /** Extra locals of a nested-resultMap body: the grouping keys. */
    private static final Pattern NESTED_RESERVED = Pattern.compile("[pr]k\\d+");

    /** Extra locals of dynamic bodies: the StringBuilder and the condition locals. */
    private static final Pattern DYNAMIC_RESERVED = Pattern.compile("sb|c\\d+");

    /** Extra locals of a {@code <foreach>} body: size, padding, entry, last element. */
    private static final Pattern FOREACH_RESERVED = Pattern.compile("[nkpe]\\d+|last\\d+|src\\d+");



    private final TypeResolver typeResolver;
    private final ColumnNaming columnNaming;
    private final Messager messager;
    private final Elements elements;
    /** Shared across mappers: one reader per result class (design red line). */
    private final Map<String, ResultModel> resultModels;

    public AnnotationFrontend(ProcessingEnvironment env, Map<String, ResultModel> resultModels,
            ColumnNaming columnNaming, TypeHandlerDefaults typeHandlerDefaults) {
        this.columnNaming = columnNaming;
        this.typeResolver = new TypeResolver(env.getElementUtils(), env.getTypeUtils(),
                columnNaming, typeHandlerDefaults);
        this.messager = env.getMessager();
        this.elements = env.getElementUtils();
        this.resultModels = resultModels;
    }

    /**
     * Parses one mapper interface. Reports every error it finds and returns
     * null if there was any — a mapper with errors is never emitted, partial
     * code is worse than no code.
     */
    public MapperModel parse(TypeElement mapper) {
        return parse(mapper, null);
    }

    /**
     * The result model of a class marked {@code @LarkBatisRow}: a reader for
     * a class no statement returns, so the manual escape hatch has one to
     * pass. Same shape as the reader a {@code resultType} gets — declaration
     * order is the canonical column order, there being no select list here to
     * take an order from.
     *
     * <p>Throws {@link LarkBatisProcessingException} for a class that cannot
     * be read into (a record, an interface, no no-arg constructor, no
     * setters); the caller reports it on the annotated class.
     */
    public ResultModel rowClass(TypeElement type) {
        return typeResolver.resultModelOf(type);
    }

    /**
     * Parses one mapper interface, with an optional mapper XML file:
     * each abstract method takes its SQL from its statement annotation or
     * from the XML statement with the same id — both or neither is an error.
     */
    public MapperModel parse(TypeElement mapper, MapperXmlParser.XmlMapper xml) {
        boolean failed = false;
        if (mapper.getKind() != ElementKind.INTERFACE) {
            error(mapper, "LarkBatis statements belong on interface methods; "
                    + mapper.getQualifiedName() + " is not an interface");
            return null;
        }
        if (mapper.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(mapper, "Mapper " + mapper.getQualifiedName()
                    + " must be a top-level interface: a nested one has no package"
                    + " of its own to generate the $$Impl into");
            return null;
        }
        if (!mapper.getTypeParameters().isEmpty()) {
            error(mapper, "Mapper " + mapper.getQualifiedName() + " must not be generic");
            return null;
        }

        String interfaceFqn = mapper.getQualifiedName().toString();
        List<StatementModel> statements = new ArrayList<>();
        Set<String> methodNames = new HashSet<>();
        for (ExecutableElement method : ElementFilter.methodsIn(mapper.getEnclosedElements())) {
            if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC)) {
                continue; // default methods are the escape hatch
            }
            if (!methodNames.add(method.getSimpleName().toString())) {
                error(method, "Overloaded mapper methods are not supported: "
                        + method.getSimpleName() + " (constant names would collide)");
                failed = true;
                continue;
            }
            try {
                statements.add(parseStatement(interfaceFqn, method, xml));
            } catch (LarkBatisProcessingException e) {
                error(e.element() != null ? e.element() : method, e.getMessage());
                failed = true;
            }
        }
        if (xml != null) {
            for (String id : xml.statements().keySet()) {
                if (!methodNames.contains(id)) {
                    error(mapper, xml.file().getFileName() + ": statement \"" + id
                            + "\" matches no abstract method of " + interfaceFqn
                            + " (default methods cannot be XML-backed)");
                    failed = true;
                }
            }
        }
        if (failed) {
            return null;
        }

        String packageName = interfaceFqn.contains(".")
                ? interfaceFqn.substring(0, interfaceFqn.lastIndexOf('.'))
                : "";
        return new MapperModel(packageName, interfaceFqn,
                mapper.getSimpleName().toString(), List.copyOf(statements));
    }

    // --- one statement ---------------------------------------------------------

    private StatementModel parseStatement(String interfaceFqn, ExecutableElement method,
            MapperXmlParser.XmlMapper xml) {
        Sql sql = sqlOf(method, xml);
        String methodName = method.getSimpleName().toString();
        String statementId = interfaceFqn + "." + methodName;

        if (TypeResolver.handlerTypeOf(method) != null) {
            // @Handler targets METHOD so that a getter can carry it. On a mapper
            // method it would have to mean "the scalar this statement returns",
            // which is a different thing wearing the same annotation — and a
            // scalar result reads column 1 with no property to hang a handler
            // on. Refused rather than ignored.
            throw new LarkBatisProcessingException(method, statementId
                    + ": @Handler on a mapper method is not supported — it names the handler for"
                    + " a result property or a parameter, not for a statement's return."
                    + " Return a bean whose property carries it, or read the row through the"
                    + " escape hatch");
        }

        // parameters, in order, named from @Param or the AST
        List<VariableElement> paramElements = new ArrayList<>(method.getParameters());
        LinkedHashMap<String, VariableElement> paramsByName = new LinkedHashMap<>();
        for (VariableElement param : paramElements) {
            Param annotation = param.getAnnotation(Param.class);
            String name = annotation != null ? annotation.value() : param.getSimpleName().toString();
            if (RESERVED_LOCALS.contains(name)) {
                throw new LarkBatisProcessingException(param, "Parameter name \"" + name
                        + "\" collides with a local of the generated body; rename it or add @Param");
            }
            if (paramsByName.putIfAbsent(name, param) != null) {
                throw new LarkBatisProcessingException(param, "Duplicate parameter name: " + name);
            }
        }
        boolean hasParamAnnotation = paramElements.stream()
                .anyMatch(p -> p.getAnnotation(Param.class) != null);

        // padding is opt-in per mapper or per method
        boolean padPow2 = method.getAnnotation(PadPow2.class) != null
                || method.getEnclosingElement().getAnnotation(PadPow2.class) != null;
        // A <foreach> insert writes its own multi-row VALUES list, so it is
        // not the single-shape addBatch() case below however much its
        // signature looks like it.
        boolean hasForeach = sql.xml != null && containsForeach(sql.xml.nodes());

        // batch: DML whose single parameter is a List<Bean>
        StatementModel.Batch batch = null;
        TypeElement batchElementType = null;
        if (sql.kind != StatementKind.SELECT && paramElements.size() == 1 && !hasForeach) {
            TypeMirror element = typeResolver.listElementOf(paramElements.get(0).asType());
            if (element != null) {
                batchElementType = typeResolver.typeElementOf(element);
                if (batchElementType == null || typeResolver.valueKindOf(element) != null) {
                    throw new LarkBatisProcessingException(paramElements.get(0),
                            "Batch element type must be a bean, got " + element);
                }
                String paramName = paramsByName.keySet().iterator().next();
                String loopVar = TypeResolver.decapitalize(batchElementType.getSimpleName().toString());
                if (loopVar.equals(paramName) || RESERVED_LOCALS.contains(loopVar)) {
                    loopVar = "item";
                }
                batch = new StatementModel.Batch(paramName, loopVar, element.toString());
            }
        }

        BindResolver resolver = new BindResolver(method, paramsByName, hasParamAnnotation,
                batch, batchElementType);

        List<SqlPiece> pieces;
        DynamicModel dynamic = null;
        if (sql.xml == null) {
            pieces = new ArrayList<>();
            for (SqlTokenizer.RawToken token : SqlTokenizer.tokenize(sql.text)) {
                if (token instanceof SqlTokenizer.RawToken.Text t) {
                    pieces.add(new SqlPiece.Text(t.text()));
                } else if (token instanceof SqlTokenizer.RawToken.Hash h) {
                    pieces.add(resolver.bind(h.expression()));
                } else if (token instanceof SqlTokenizer.RawToken.Dollar d) {
                    pieces.add(resolver.dollar(d.expression()));
                }
            }
        } else {
            MethodExprTypes exprTypes =
                    new MethodExprTypes(typeResolver, method, paramsByName, hasParamAnnotation);
            DynamicLowering.Lowered lowered = DynamicLowering.lower(sql.xml.nodes(),
                    new DynamicLowering.TokenLowerer() {
                        @Override
                        public SqlPiece hash(String expression) {
                            return resolver.bind(expression);
                        }

                        @Override
                        public SqlPiece dollar(String expression) {
                            return resolver.dollar(expression);
                        }

                        @Override
                        public void enterForeach(DynNode.Foreach node) {
                            resolver.enterForeach(node, padPow2);
                        }

                        @Override
                        public DynamicLowering.ForeachPlan exitForeach() {
                            return resolver.finishForeach();
                        }
                    },
                    test -> {
                        try {
                            return ExprCompiler.compileBoolean(test, exprTypes);
                        } catch (LarkBatisProcessingException e) {
                            throw e.element() != null ? e
                                    : new LarkBatisProcessingException(method,
                                            statementId + " <if " + e.getMessage() + ">");
                        }
                    });
            pieces = new ArrayList<>(lowered.flatPieces());
            if (lowered.dynamic()) {
                dynamic = lowered.model();
                if (batch != null) {
                    throw new LarkBatisProcessingException(method, statementId
                            + ": batch statements cannot use dynamic SQL — addBatch() needs"
                            + " one SQL shape for every element");
                }
                for (String name : paramsByName.keySet()) {
                    if (DYNAMIC_RESERVED.matcher(name).matches()
                            || hasForeach && FOREACH_RESERVED.matcher(name).matches()) {
                        throw new LarkBatisProcessingException(paramsByName.get(name),
                                "Parameter name \"" + name + "\" collides with a local of the"
                                        + " generated dynamic body; rename it or add @Param");
                    }
                }
            }
            if (padPow2) {
                validatePadding(pieces, sql.kind, method, statementId);
            }
            if (pieces.isEmpty()) {
                throw new LarkBatisProcessingException(method, "Empty SQL");
            }
        }

        List<ParamModel> params = new ArrayList<>();
        paramsByName.forEach((name, element) -> params.add(new ParamModel(name, element.asType().toString())));

        MapperXmlParser.XmlResultMap resultMap = sql.xml == null || sql.xml.resultMap() == null
                ? null : xml.resultMaps().get(sql.xml.resultMap());
        Return ret = returnOf(method, sql.kind, readerPieces(pieces, dynamic), statementId,
                resultMap);
        if (sql.xml != null && sql.xml.resultType() != null && sql.kind == StatementKind.SELECT) {
            validateResultType(method, sql.xml.resultType(), ret);
        }
        if (ret.nested != null) {
            for (String name : paramsByName.keySet()) {
                if (NESTED_RESERVED.matcher(name).matches()) {
                    throw new LarkBatisProcessingException(paramsByName.get(name),
                            "Parameter name \"" + name + "\" collides with a grouping-key local"
                                    + " of the generated nested-resultMap body; rename it or"
                                    + " add @Param");
                }
            }
        }
        KeyModel keys = keysOf(method, sql, paramsByName, hasParamAnnotation, batch,
                batchElementType, hasForeach);

        return new StatementModel(methodName, statementId, sql.kind, List.copyOf(pieces),
                List.copyOf(params), ret.shape, ret.returnTypeFqn, ret.scalarKind, ret.scalarEnumType,
                ret.resultFqn, ret.readerAccess, keys, batch, dynamic, ret.nested);
    }

    /**
     * The pieces the select-list parser may trust. In a dynamic statement,
     * only a leading unconditional segment is guaranteed to be present in
     * every variant — a select list touched by dynamic tags downgrades to the
     * name-based reader.
     */
    /** Set when {@link #readerPieces} truncated because of a {@code <foreach>}. */
    private static final String FOREACH_IN_SELECT_LIST =
            "a <foreach> in the select list: the column count is not known at build time";

    private static List<SqlPiece> readerPieces(List<SqlPiece> pieces, DynamicModel dynamic) {
        if (dynamic == null) {
            return pieces;
        }
        DynamicModel.Segment first = dynamic.segments().get(0);
        if (first.guard() != null) {
            return List.of();
        }
        List<SqlPiece> safe = new ArrayList<>();
        for (SqlPiece piece : first.pieces()) {
            // An Alt varies the text; a <foreach> varies how many columns
            // there are. Neither can be read positionally, and everything
            // after one is at an unknown position too.
            if (piece instanceof SqlPiece.Alt || piece instanceof SqlPiece.Foreach) {
                break;
            }
            safe.add(piece);
        }
        return safe;
    }

    private record Sql(StatementKind kind, String text, MapperXmlParser.XmlStatement xml) {
    }

    private Sql sqlOf(ExecutableElement method, MapperXmlParser.XmlMapper xml) {
        List<Sql> found = new ArrayList<>();
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            found.add(new Sql(StatementKind.SELECT, joinSql(select.value()), null));
        }
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) {
            found.add(new Sql(StatementKind.INSERT, joinSql(insert.value()), null));
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            found.add(new Sql(StatementKind.UPDATE, joinSql(update.value()), null));
        }
        Delete delete = method.getAnnotation(Delete.class);
        if (delete != null) {
            found.add(new Sql(StatementKind.DELETE, joinSql(delete.value()), null));
        }
        MapperXmlParser.XmlStatement xmlStatement = xml == null ? null
                : xml.statements().get(method.getSimpleName().toString());
        if (xmlStatement != null) {
            if (!found.isEmpty()) {
                throw new LarkBatisProcessingException(method, "Method "
                        + method.getSimpleName() + " has both a statement annotation and an XML"
                        + " statement (" + xml.file().getFileName() + "); keep exactly one");
            }
            return new Sql(xmlStatement.kind(), null, xmlStatement);
        }
        if (found.size() != 1) {
            throw new LarkBatisProcessingException(method, found.isEmpty()
                    ? "Abstract mapper method without a statement annotation"
                            + (xml != null ? " or an XML statement id \""
                                    + method.getSimpleName() + "\" in " + xml.file().getFileName()
                                    : "")
                            + "; hand-written logic belongs in a default method"
                    : "Exactly one of @Select/@Insert/@Update/@Delete is allowed");
        }
        if (found.get(0).text.isEmpty()) {
            throw new LarkBatisProcessingException(method, "Empty SQL");
        }
        return found.get(0);
    }

    /**
     * The XML resultType attribute is redundant with the method signature —
     * it stays because the differential harness feeds the same file to
     * MyBatis — but a value contradicting the signature would lie to readers.
     */
    private void validateResultType(ExecutableElement method, String resultType, Return ret) {
        String expected = ret.resultFqn != null ? ret.resultFqn
                : scalarFqnOf(ret.scalarKind, ret.scalarEnumType);
        if (expected == null) {
            return;
        }
        String simple = expected.substring(expected.lastIndexOf('.') + 1);
        if (resultType.equals(expected) || resultType.equalsIgnoreCase(simple)
                || resultType.equals(aliasOf(expected))) {
            return;
        }
        throw new LarkBatisProcessingException(method, "resultType=\"" + resultType
                + "\" contradicts the method signature, which implies " + expected);
    }

    private static String scalarFqnOf(ValueKind kind, String enumType) {
        return switch (kind) {
            case PRIM_BOOLEAN, BOX_BOOLEAN -> "java.lang.Boolean";
            case PRIM_BYTE, BOX_BYTE -> "java.lang.Byte";
            case PRIM_SHORT, BOX_SHORT -> "java.lang.Short";
            case PRIM_INT, BOX_INT -> "java.lang.Integer";
            case PRIM_LONG, BOX_LONG -> "java.lang.Long";
            case PRIM_FLOAT, BOX_FLOAT -> "java.lang.Float";
            case PRIM_DOUBLE, BOX_DOUBLE -> "java.lang.Double";
            case PRIM_CHAR, BOX_CHARACTER -> "java.lang.Character";
            case STRING -> "java.lang.String";
            case BIG_DECIMAL -> "java.math.BigDecimal";
            case BIG_INTEGER -> "java.math.BigInteger";
            case LOCAL_DATE -> "java.time.LocalDate";
            case LOCAL_TIME -> "java.time.LocalTime";
            case LOCAL_DATE_TIME -> "java.time.LocalDateTime";
            case INSTANT -> "java.time.Instant";
            case OFFSET_DATE_TIME -> "java.time.OffsetDateTime";
            case OFFSET_TIME -> "java.time.OffsetTime";
            case ZONED_DATE_TIME -> "java.time.ZonedDateTime";
            case SQL_DATE -> "java.sql.Date";
            case SQL_TIME -> "java.sql.Time";
            case SQL_TIMESTAMP -> "java.sql.Timestamp";
            case UTIL_DATE -> "java.util.Date";
            case ENUM -> enumType;
            case BYTES -> null;
        };
    }

    /** The MyBatis type aliases the fixtures actually use. */
    private static String aliasOf(String fqn) {
        return switch (fqn) {
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.String" -> "string";
            case "java.math.BigDecimal" -> "decimal";
            default -> fqn;
        };
    }

    /** MyBatis joins annotation SQL with a single space (MapperAnnotationBuilder). */
    private static String joinSql(String[] values) {
        return String.join(" ", values).trim();
    }

    // --- #{} / ${} resolution ----------------------------------------------------

    /** A resolved value expression: how generated code reads it, and its type. */
    /**
     * @param handlerSite the element a {@code @Handler} for this value would sit
     *                    on: the parameter when the bind names a parameter, the
     *                    property's field/getter/setter when it names a property,
     *                    null when neither can carry one (a {@code <foreach>}
     *                    loop variable). Carried rather than recomputed because
     *                    only {@code resolve} knows which bean the last path
     *                    segment came off.
     */
    private record Resolved(String accessor, TypeMirror type, VariableElement rootParam,
                            boolean isFragment, Element handlerSite) {

        /** A value whose only possible handler site is the parameter itself. */
        Resolved(String accessor, TypeMirror type, VariableElement rootParam, boolean isFragment) {
            this(accessor, type, rootParam, isFragment, rootParam);
        }
    }

    private static boolean containsForeach(List<DynNode> nodes) {
        for (DynNode node : nodes) {
            if (node instanceof DynNode.Foreach) {
                return true;
            }
            List<DynNode> children = childrenOf(node);
            if (children != null && containsForeach(children)) {
                return true;
            }
            if (node instanceof DynNode.Choose choose
                    && (choose.whens().stream().anyMatch(w -> containsForeach(w.children()))
                            || containsForeach(choose.otherwise()))) {
                return true;
            }
        }
        return false;
    }

    private static List<DynNode> childrenOf(DynNode node) {
        if (node instanceof DynNode.If ifNode) {
            return ifNode.children();
        }
        if (node instanceof DynNode.Trim trim) {
            return trim.children();
        }
        return null;
    }

    /**
     * {@code @PadPow2} repeats the last element to fill the padded slots, so
     * it is only invisible where duplicates cannot change the result: an
     * {@code IN} list. Anything else — a multi-row {@code VALUES}, a body
     * with literal text of its own — would silently change what the statement
     * does, so it is refused.
     */
    private static void validatePadding(List<SqlPiece> pieces, StatementKind kind,
            ExecutableElement method, String statementId) {
        for (int i = 0; i < pieces.size(); i++) {
            if (!(pieces.get(i) instanceof SqlPiece.Foreach foreach)) {
                continue;
            }
            validatePadding(foreach.body(), kind, method, statementId);
            if (!foreach.pad()) {
                continue;
            }
            String where = statementId + " <foreach collection=\"" + foreach.label() + "\">";
            if (kind == StatementKind.INSERT) {
                throw new LarkBatisProcessingException(method, where
                        + ": @PadPow2 repeats the last element, which on an INSERT would"
                        + " insert duplicate rows. Remove @PadPow2 from this method.");
            }
            long binds = foreach.body().stream().filter(p -> p instanceof SqlPiece.Bind).count();
            boolean onlyBlankText = foreach.body().stream()
                    .allMatch(p -> p instanceof SqlPiece.Bind
                            || p instanceof SqlPiece.Text text && text.sql().isBlank());
            if (binds != 1 || !onlyBlankText || !isInList(pieces, i, foreach)) {
                throw new LarkBatisProcessingException(method, where
                        + ": @PadPow2 applies to an IN list — `... IN (#{item})` — because that"
                        + " is the only shape where repeating the last element cannot change the"
                        + " result. Remove @PadPow2 from this method.");
            }
        }
    }

    /**
     * Whether this loop really is the right-hand side of an {@code IN}:
     * {@code ... IN} immediately before, and {@code ( , )} around the
     * elements. Anything else — {@code ARRAY[...]}, a {@code VALUES} tuple, an
     * {@code OR} chain — either counts its elements or repeats them visibly.
     */
    private static boolean isInList(List<SqlPiece> pieces, int index, SqlPiece.Foreach foreach) {
        if (!"(".equals(trimmed(foreach.open())) || !")".equals(trimmed(foreach.close()))
                || !",".equals(trimmed(foreach.separator()))) {
            return false;
        }
        if (index == 0 || !(pieces.get(index - 1) instanceof SqlPiece.Text before)) {
            return false;
        }
        String text = before.sql().stripTrailing().toUpperCase(java.util.Locale.ENGLISH);
        return text.equals("IN") || text.endsWith(" IN") || text.endsWith("\tIN")
                || text.endsWith("\nIN");
    }

    private static String trimmed(String literal) {
        return literal == null ? null : literal.trim();
    }

    /**
     * One {@code <foreach>} in scope: its {@code item} and {@code index}
     * names shadow the mapper's parameters inside the body, exactly as
     * {@code ForEachSqlNode} shadows them in {@code DynamicContext.bindings}
     * — except that here the shadowing is a Java local and the compiler
     * enforces it.
     */
    private static final class ForeachScope {
        final String itemName;
        final String itemExpr;
        final TypeMirror itemType;
        final String indexName;
        final String indexExpr;
        final TypeMirror indexType;
        /** The plan, minus what only the lowered body can tell us. */
        final DynamicLowering.ForeachPlan draft;
        final SqlPiece.Foreach.Local itemLocal;
        final SqlPiece.Foreach.Local indexLocal;
        boolean itemUsed;
        boolean indexUsed;

        ForeachScope(String itemName, String itemExpr, TypeMirror itemType, String indexName,
                String indexExpr, TypeMirror indexType, DynamicLowering.ForeachPlan draft,
                SqlPiece.Foreach.Local itemLocal, SqlPiece.Foreach.Local indexLocal) {
            this.itemName = itemName;
            this.itemExpr = itemExpr;
            this.itemType = itemType;
            this.indexName = indexName;
            this.indexExpr = indexExpr;
            this.indexType = indexType;
            this.draft = draft;
            this.itemLocal = itemLocal;
            this.indexLocal = indexLocal;
        }

        String itemName() {
            return itemName;
        }

        String indexName() {
            return indexName;
        }

        String itemExpr() {
            return itemExpr;
        }

        String indexExpr() {
            return indexExpr;
        }

        TypeMirror itemType() {
            return itemType;
        }

        TypeMirror indexType() {
            return indexType;
        }
    }

    private final class BindResolver {
        private final ExecutableElement method;
        private final LinkedHashMap<String, VariableElement> params;
        private final boolean hasParamAnnotation;
        private final StatementModel.Batch batch;
        private final TypeElement batchElementType;
        /** Innermost <foreach> last; nested loops resolve outward. */
        private final List<ForeachScope> foreachScopes = new ArrayList<>();
        private int foreachDepth;

        BindResolver(ExecutableElement method, LinkedHashMap<String, VariableElement> params,
                boolean hasParamAnnotation, StatementModel.Batch batch, TypeElement batchElementType) {
            this.method = method;
            this.params = params;
            this.hasParamAnnotation = hasParamAnnotation;
            this.batch = batch;
            this.batchElementType = batchElementType;
        }

        SqlPiece.Bind bind(String expression) {
            // MyBatis allows #{prop, jdbcType=..., typeHandler=..., ...}. Most of
            // those attributes stood in for decisions made at build time here and
            // are accepted and ignored — but typeHandler decides what the value
            // *means*, so it is read. Ignoring it silently is how an ordinal enum
            // column turns into a name one on the way across.
            String property = expression.split(",", 2)[0].trim();
            String inlineHandler = inlineAttribute(expression, "typeHandler");
            Resolved value = resolve(property, expression, false,
                    handlerInPlay(property, inlineHandler));
            if (value.isFragment()) {
                throw new LarkBatisProcessingException(value.rootParam(),
                        "#{" + expression + "}: SqlFragment splices via ${}, not #{}");
            }
            String where = "#{" + expression + "}";
            String handler = handlerFor(where, inlineHandler, value);
            ValueKind kind = typeResolver.valueKindOf(value.type());
            if (kind == null && handler == null) {
                throw new LarkBatisProcessingException(
                        value.rootParam() != null ? value.rootParam() : method,
                        where + " has unsupported type " + value.type()
                                + ". Supported: primitives and their boxes, String, BigDecimal, BigInteger,"
                                + " byte[], java.time (LocalDate/LocalTime/LocalDateTime/"
                                + "Instant/OffsetDateTime/OffsetTime/ZonedDateTime),"
                                + " java.util.Date, java.sql.Date/Time/Timestamp, enums,"
                                + " or any type a @Handler or an -Alarkbatis.typeHandlers"
                                + " entry moves.");
            }
            return new SqlPiece.Bind(expression, value.accessor(), kind,
                    typeResolver.enumTypeOf(value.type()), handler);
        }

        /**
         * The handler this bind moves through. An inline {@code typeHandler}
         * beats a {@code @Handler} on the declaration for the same reason a
         * {@code <resultMap>} beats {@code @Column}: naming it in the statement
         * is the more specific of the two.
         */
        private String handlerFor(String where, String inlineHandler, Resolved value) {
            if (inlineHandler != null) {
                TypeElement declared = elements.getTypeElement(inlineHandler);
                if (declared == null) {
                    throw new LarkBatisProcessingException(method, where + ": typeHandler names"
                            + " \"" + inlineHandler + "\", which is not on the compilation"
                            + " classpath" + (inlineHandler.indexOf('.') < 0
                                    ? " — type aliases are resolved at build time, so write the"
                                            + " fully-qualified class name"
                                    : ""));
                }
                return typeResolver.validateHandler(
                        value.rootParam() != null ? value.rootParam() : method,
                        declared.asType(), value.type(), where);
            }
            if (value.handlerSite() != null) {
                TypeMirror declared = TypeResolver.handlerTypeOf(value.handlerSite());
                if (declared != null) {
                    return typeResolver.validateHandler(value.handlerSite(), declared,
                            value.type(), where);
                }
            }
            // last: the build-wide default for this type, already validated
            return typeResolver.defaultHandlerFor(value.type());
        }

        /**
         * One attribute of a {@code #{}} occurrence, or null. MyBatis parses
         * these into a ParameterMapping at runtime; here the whole occurrence
         * is gone by the time the code runs, so this is the only place the text
         * is ever read.
         */
        private String inlineAttribute(String expression, String name) {
            String[] parts = expression.split(",");
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                    String value = part.substring(eq + 1).trim();
                    if (value.isEmpty()) {
                        throw new LarkBatisProcessingException(method, "#{" + expression
                                + "}: " + name + " is empty");
                    }
                    return value;
                }
            }
            return null;
        }

        SqlPiece.Dollar dollar(String expression) {
            String property = expression.trim();
            Resolved value = resolve(property, expression, true, false);
            if (value.isFragment()) {
                return new SqlPiece.Dollar(expression, SqlPiece.Dollar.DollarKind.FRAGMENT,
                        value.accessor(), List.of());
            }
            ValueKind kind = typeResolver.valueKindOf(value.type());
            if (kind == ValueKind.STRING) {
                OrderBy orderBy = value.rootParam() == null ? null
                        : value.rootParam().getAnnotation(OrderBy.class);
                boolean isBareParam = value.accessor().equals(nameOf(value.rootParam()));
                if (orderBy != null && isBareParam) {
                    return new SqlPiece.Dollar(expression, SqlPiece.Dollar.DollarKind.ORDER_BY,
                            value.accessor(), List.of(orderBy.allowed()));
                }
                throw new LarkBatisProcessingException(
                        value.rootParam() != null ? value.rootParam() : method,
                        "${" + expression + "} would splice a String into SQL. Use SqlFragment"
                                + " (audited at the call site) or @OrderBy(allowed = {...})"
                                + " on the parameter.");
            }
            if (kind == ValueKind.ENUM) {
                return new SqlPiece.Dollar(expression, SqlPiece.Dollar.DollarKind.ENUM_NAME,
                        value.accessor(), List.of());
            }
            if (kind != null && kind.closedValueSet()) {
                return new SqlPiece.Dollar(expression, SqlPiece.Dollar.DollarKind.CLOSED_VALUE,
                        value.accessor(), List.of());
            }
            if (kind != null && !kind.primitive()) {
                switch (kind) {
                    case BOX_BOOLEAN, BOX_BYTE, BOX_SHORT, BOX_INT, BOX_LONG:
                        throw new LarkBatisProcessingException(method,
                                "${" + expression + "}: use the primitive type — a null "
                                        + value.type() + " would splice the text \"null\" into SQL");
                    default:
                        // fall through to the generic rejection below
                }
            }
            throw new LarkBatisProcessingException(method,
                    "${" + expression + "} has type " + value.type()
                            + "; only SqlFragment, int/long/short/byte/boolean, enums,"
                            + " or @OrderBy String parameters may splice");
        }

        // --- <foreach> ----------------------------------------------------------

        /**
         * Resolves the collection and puts {@code item}/{@code index} in
         * scope for the body. The loop variables are named exactly as the XML
         * names them, so the generated body reads like the mapper (design red
         * line 8) — which also means a name colliding with a method parameter
         * would not compile, and is refused here with the fix instead.
         */
        void enterForeach(DynNode.Foreach node, boolean pad) {
            String where = "<foreach collection=\"" + node.collection() + "\">";
            Resolved collection = resolveCollection(node.collection(), where);
            TypeResolver.Iteration iteration = typeResolver.iterationOf(collection.type());
            if (iteration == null) {
                throw new LarkBatisProcessingException(
                        collection.rootParam() != null ? collection.rootParam() : method,
                        where + " has type " + collection.type() + "; <foreach> needs a"
                                + " statically-typed Collection<T>, T[] or Map<K,V>."
                                + " A raw or wildcard type has no element type to bind.");
            }
            if (typeResolver.valueKindOf(iteration.elementType()) == null
                    && typeResolver.typeElementOf(iteration.elementType()) == null) {
                throw new LarkBatisProcessingException(method, where + " iterates "
                        + iteration.elementType() + ", which is neither a supported value type"
                        + " nor a bean");
            }

            int depth = foreachDepth++;
            String itemName = node.item();
            String indexName = node.index();
            requireFreeName(itemName, where, "item");
            requireFreeName(indexName, where, "index");
            if (itemName != null && itemName.equals(indexName)) {
                throw new LarkBatisProcessingException(method,
                        where + ": item and index cannot share the name \"" + itemName + "\"");
            }

            String loopVar;
            String itemExpr;
            String indexExpr;
            String counterVar = null;
            SqlPiece.Foreach.Local itemLocal = null;
            SqlPiece.Foreach.Local indexLocal = null;
            if (iteration.kind() == SqlPiece.Foreach.Iteration.MAP_ENTRY) {
                // Map iteration binds the key to index and the value to item
                // (MyBatis issue #709, ForEachSqlNode.apply). The entry
                // itself needs a name of its own, which the XML never gives.
                loopVar = "e" + depth;
                itemExpr = loopVar + ".getValue()";
                indexExpr = loopVar + ".getKey()";
                if (itemName != null) {
                    itemLocal = new SqlPiece.Foreach.Local(itemName,
                            iteration.elementType().toString(), itemExpr);
                    itemExpr = itemName;
                }
                if (indexName != null) {
                    indexLocal = new SqlPiece.Foreach.Local(indexName,
                            iteration.indexType().toString(), indexExpr);
                    indexExpr = indexName;
                }
            } else {
                if (itemName == null) {
                    throw new LarkBatisProcessingException(method, where
                            + " needs an item attribute: without a name the body cannot"
                            + " reference the element");
                }
                loopVar = itemName;
                itemExpr = itemName;
                // the position is a counter the emitter maintains, named by
                // the XML so the body reads the way the mapper wrote it
                indexExpr = indexName;
                counterVar = indexName;
            }

            // Padding a nested loop would multiply the variant count instead
            // of bounding it — the whole point of the padding — so it stays
            // on the outermost loop only.
            boolean padThisLoop = pad && foreachScopes.isEmpty();
            DynamicLowering.ForeachPlan draft = new DynamicLowering.ForeachPlan(
                    node.collection(), collection.accessor(), collection.type().toString(),
                    sizeExpression(collection.accessor(), iteration.kind()), iteration.kind(),
                    loopVar, iteration.loopType(), List.of(), counterVar, padThisLoop);
            foreachScopes.add(new ForeachScope(itemName, itemExpr, iteration.elementType(),
                    indexName, indexExpr, iteration.indexType(), draft, itemLocal, indexLocal));
        }

        /**
         * Completes the plan now that the body is lowered: a loop variable the
         * body never reads is not declared, because generated code that
         * declares locals nobody uses is generated code nobody trusts.
         */
        DynamicLowering.ForeachPlan finishForeach() {
            ForeachScope scope = foreachScopes.remove(foreachScopes.size() - 1);
            List<SqlPiece.Foreach.Local> locals = new ArrayList<>();
            if (scope.itemUsed && scope.itemLocal != null) {
                locals.add(scope.itemLocal);
            }
            if (scope.indexUsed && scope.indexLocal != null) {
                locals.add(scope.indexLocal);
            }
            DynamicLowering.ForeachPlan draft = scope.draft;
            return new DynamicLowering.ForeachPlan(draft.label(), draft.accessor(),
                    draft.collectionTypeFqn(),
                    draft.sizeExpr(), draft.iteration(), draft.loopVar(), draft.loopVarTypeFqn(),
                    List.copyOf(locals), scope.indexUsed ? draft.counterVar() : null,
                    draft.pad());
        }



        /**
         * Resolves {@code collection="..."}. Separate from {@code #{}}
         * resolution because the answer is a collection, which is exactly the
         * type {@code #{}} refuses to bind.
         *
         * <p>The MyBatis aliases are accepted for a sole unannotated
         * parameter, with the same rule ParamNameResolver applies
         * (wrapToMapIfCollection): {@code collection} for any Collection,
         * {@code list} only for a List, {@code array} only for an array, and
         * nothing at all for a Map. They are all over existing mappers.
         */
        private Resolved resolveCollection(String expression, String where) {
            List<String> path = List.of(expression.trim().split("\\.", -1));
            if (path.stream().anyMatch(seg -> !IDENTIFIER.matcher(seg).matches())) {
                throw new LarkBatisProcessingException(method, where
                        + " is not a simple property path; OGNL expressions were dropped"
                        + "");
            }
            Resolved fromScope = fromForeachScope(path, where);
            if (fromScope != null) {
                return fromScope;
            }

            String head = path.get(0);
            VariableElement param = params.get(head);
            String paramName = head;
            if (param == null && params.size() == 1 && !hasParamAnnotation) {
                Map.Entry<String, VariableElement> sole = params.entrySet().iterator().next();
                if (path.size() == 1 && isCollectionAlias(head, sole.getValue().asType())) {
                    param = sole.getValue();
                    paramName = sole.getKey();
                } else if (path.size() == 1) {
                    // sole bean parameter: the collection is one of its properties
                    TypeElement bean = typeResolver.typeElementOf(sole.getValue().asType());
                    if (bean != null) {
                        TypeResolver.PropertyRead read =
                                typeResolver.getterFor(bean, head, method);
                        return new Resolved(sole.getKey() + "." + read.accessorCall(),
                                read.type(), sole.getValue(), false,
                                typeResolver.handlerSiteOn(bean, head));
                    }
                }
            }
            if (param == null) {
                throw new LarkBatisProcessingException(method, where
                        + " does not match any parameter. Available: "
                        + String.join(", ", params.keySet()));
            }

            Resolved resolved = new Resolved(paramName, param.asType(), param, false);
            for (int hop = 1; hop < path.size(); hop++) {
                TypeElement bean = typeResolver.typeElementOf(resolved.type());
                if (bean == null) {
                    throw new LarkBatisProcessingException(param, where + ": "
                            + resolved.type() + " has no property \"" + path.get(hop) + "\"");
                }
                TypeResolver.PropertyRead read =
                        typeResolver.getterFor(bean, path.get(hop), method);
                resolved = new Resolved(resolved.accessor() + "." + read.accessorCall(),
                        read.type(), param, false,
                        typeResolver.handlerSiteOn(bean, path.get(hop)));
            }
            return resolved;
        }

        private String sizeExpression(String accessor, SqlPiece.Foreach.Iteration kind) {
            return kind == SqlPiece.Foreach.Iteration.ARRAY
                    ? accessor + ".length"
                    : accessor + ".size()";
        }

        private void requireFreeName(String name, String where, String attribute) {
            if (name == null) {
                return;
            }
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new LarkBatisProcessingException(method,
                        where + ": " + attribute + "=\"" + name + "\" is not a Java identifier");
            }
            if (params.containsKey(name)) {
                throw new LarkBatisProcessingException(params.get(name),
                        where + ": " + attribute + "=\"" + name + "\" collides with the"
                                + " parameter of the same name — the generated loop variable"
                                + " would shadow it. Rename the " + attribute + ".");
            }
            if (RESERVED_LOCALS.contains(name) || DYNAMIC_RESERVED.matcher(name).matches()
                    || FOREACH_RESERVED.matcher(name).matches()) {
                throw new LarkBatisProcessingException(method,
                        where + ": " + attribute + "=\"" + name + "\" collides with a local of"
                                + " the generated body; rename it");
            }
            for (ForeachScope scope : foreachScopes) {
                if (name.equals(scope.itemName()) || name.equals(scope.indexName())) {
                    throw new LarkBatisProcessingException(method,
                            where + ": " + attribute + "=\"" + name + "\" is already in scope"
                                    + " from an enclosing <foreach>");
                }
            }
        }

        /**
         * ParamNameResolver.wrapToMapIfCollection: a sole Collection gets the
         * key "collection", a List additionally "list", an array "array" — and
         * a Map is not wrapped at all, so it has no alias.
         */
        private boolean isCollectionAlias(String name, TypeMirror type) {
            return switch (name) {
                case "collection" -> typeResolver.isAssignableTo(type, "java.util.Collection");
                case "list" -> typeResolver.isAssignableTo(type, "java.util.List");
                case "array" -> type.getKind() == TypeKind.ARRAY;
                default -> false;
            };
        }

        /** The innermost scope binding {@code name}, or null. */
        private Resolved fromForeachScope(List<String> path, String where) {
            for (int i = foreachScopes.size() - 1; i >= 0; i--) {
                ForeachScope scope = foreachScopes.get(i);
                String head = path.get(0);
                if (head.equals(scope.itemName())) {
                    scope.itemUsed = true;
                    return navigate(scope.itemExpr(), scope.itemType(), path, where);
                }
                if (head.equals(scope.indexName())) {
                    scope.indexUsed = true;
                    return navigate(scope.indexExpr(), scope.indexType(), path, where);
                }
            }
            return null;
        }

        /** One property hop off a loop variable, matching the one-hop rule for binds. */
        private Resolved navigate(String expr, TypeMirror type, List<String> path, String where) {
            if (path.size() == 1) {
                // the fragment flag has to travel with the value: a
                // SqlFragment element is spliceable exactly like a
                // SqlFragment parameter
                return new Resolved(expr, type, null, typeResolver.isSqlFragment(type));
            }
            if (path.size() > 2) {
                throw new LarkBatisProcessingException(method, where
                        + ": binds navigate one property level");
            }
            TypeElement bean = typeResolver.typeElementOf(type);
            if (bean == null) {
                throw new LarkBatisProcessingException(method, where
                        + ": " + type + " has no properties");
            }
            TypeResolver.PropertyRead read = typeResolver.getterFor(bean, path.get(1), method);
            return new Resolved(expr + "." + read.accessorCall(), read.type(), null,
                    typeResolver.isSqlFragment(read.type()),
                    typeResolver.handlerSiteOn(bean, path.get(1)));
        }

        private String nameOf(VariableElement param) {
            if (param == null) {
                return null;
            }
            for (Map.Entry<String, VariableElement> e : params.entrySet()) {
                if (e.getValue() == param) {
                    return e.getKey();
                }
            }
            return null;
        }

        /**
         * Whether a handler will move this value, decided before the value is
         * resolved. {@code resolve} rejects a parameter whose type is outside
         * the whitelist, and that check would otherwise fire on exactly the
         * types a handler exists to carry.
         */
        private boolean handlerInPlay(String property, String inlineHandler) {
            if (inlineHandler != null) {
                return true;
            }
            List<String> path = List.of(property.split("\\.", -1));
            if (path.size() != 1) {
                return false;
            }
            VariableElement param = params.size() == 1 && !hasParamAnnotation
                    ? params.values().iterator().next()
                    : params.get(path.get(0));
            if (param == null) {
                return false;
            }
            return TypeResolver.handlerTypeOf(param) != null
                    || typeResolver.hasDefaultHandlerFor(param.asType());
        }

        private Resolved resolve(String property, String rawExpression, boolean dollar) {
            return resolve(property, rawExpression, dollar, false);
        }

        private Resolved resolve(String property, String rawExpression, boolean dollar,
                boolean handled) {
            String where = (dollar ? "${" : "#{") + rawExpression + "}";
            List<String> path = List.of(property.split("\\.", -1));
            if (path.isEmpty() || path.stream().anyMatch(seg -> !IDENTIFIER.matcher(seg).matches())) {
                throw new LarkBatisProcessingException(method, where
                        + " is not a simple property path; OGNL expressions were dropped");
            }

            // <foreach> item/index shadow the parameters inside the body,
            // matching ForEachSqlNode's bindings
            Resolved fromScope = fromForeachScope(path, where);
            if (fromScope != null) {
                return fromScope;
            }

            if (batch != null) {
                // batch bodies bind against the loop element
                if (path.size() > 1) {
                    throw new LarkBatisProcessingException(method, where
                            + ": batch statements bind element properties directly (one level)");
                }
                TypeResolver.PropertyRead read =
                        typeResolver.getterFor(batchElementType, path.get(0), method);
                return new Resolved(batch.loopVar() + "." + read.accessorCall(), read.type(),
                        params.values().iterator().next(), false,
                        typeResolver.handlerSiteOn(batchElementType, path.get(0)));
            }

            if (params.size() == 1 && !hasParamAnnotation) {
                Map.Entry<String, VariableElement> sole = params.entrySet().iterator().next();
                String paramName = sole.getKey();
                VariableElement param = sole.getValue();
                TypeMirror paramType = param.asType();
                if (typeResolver.isSqlFragment(paramType)) {
                    requireSingleSegment(path, where, paramName);
                    requireNamed(path.get(0), paramName, where, param);
                    return new Resolved(paramName, paramType, param, true);
                }
                if (typeResolver.valueKindOf(paramType) != null
                        || (handled && path.size() == 1 && path.get(0).equals(paramName))) {
                    requireSingleSegment(path, where, paramName);
                    requireNamed(path.get(0), paramName, where, param);
                    return new Resolved(paramName, paramType, param, false);
                }
                // single bean parameter: properties are referenced directly,
                // matching MyBatis (a lone unannotated param is passed as the
                // parameter object itself — ParamNameResolver.getNamedParams)
                TypeElement bean = beanOf(paramType, param, where);
                if (path.size() > 1) {
                    String hint = path.get(0).equals(paramName)
                            ? " (the single parameter's properties are referenced directly: #{"
                                    + path.get(1) + "})"
                            : " (a bind reaches one property level only:"
                                    + " #{param.property}, not #{param.a.b})";
                    throw new LarkBatisProcessingException(method, where + " does not resolve" + hint);
                }
                TypeResolver.PropertyRead read;
                try {
                    read = typeResolver.getterFor(bean, path.get(0), method);
                } catch (LarkBatisProcessingException noProperty) {
                    if (path.get(0).equals(paramName)) {
                        // the user meant the parameter itself, and its type is
                        // outside the whitelist — say that, not "no property"
                        throw new LarkBatisProcessingException(param, where
                                + " has unsupported type " + paramType
                                + ". Supported: primitives and their boxes, String, BigDecimal, BigInteger,"
                                + " byte[], java.time (LocalDate/LocalTime/LocalDateTime/"
                                + "Instant/OffsetDateTime/OffsetTime/ZonedDateTime),"
                                + " java.util.Date, java.sql.Date/Time/Timestamp, enums,"
                                + " or a bean whose property you reference.");
                    }
                    throw noProperty;
                }
                return new Resolved(paramName + "." + read.accessorCall(), read.type(), param,
                        false, typeResolver.handlerSiteOn(bean, path.get(0)));
            }

            // multiple parameters, or @Param present: first segment names the parameter
            String first = path.get(0);
            VariableElement param = params.get(first);
            String paramName = first;
            if (param == null) {
                int generic = genericIndexOf(first);
                if (generic >= 1 && generic <= params.size()) {
                    var iterator = params.entrySet().iterator();
                    Map.Entry<String, VariableElement> entry = iterator.next();
                    for (int i = 1; i < generic; i++) {
                        entry = iterator.next();
                    }
                    param = entry.getValue();
                    paramName = entry.getKey();
                }
            }
            if (param == null) {
                throw new LarkBatisProcessingException(method, where
                        + " does not match any parameter. Available: "
                        + String.join(", ", params.keySet()) + syntheticNamesHint());
            }
            TypeMirror paramType = param.asType();
            if (typeResolver.isSqlFragment(paramType)) {
                requireSingleSegment(path, where, paramName);
                return new Resolved(paramName, paramType, param, true);
            }
            if (typeResolver.valueKindOf(paramType) != null
                    || (handled && path.size() == 1)) {
                requireSingleSegment(path, where, paramName);
                return new Resolved(paramName, paramType, param, false);
            }
            TypeElement bean = beanOf(paramType, param, where);
            if (path.size() == 1) {
                throw new LarkBatisProcessingException(method, where
                        + ": parameter " + paramName + " is a bean; bind one of its properties"
                        + " (e.g. " + (dollar ? "${" : "#{") + paramName + ".property})");
            }
            if (path.size() > 2) {
                throw new LarkBatisProcessingException(method, where
                        + ": a bind reaches one property level only —"
                        + " #{" + paramName + ".property}, not #{" + paramName + ".a.b}."
                        + " Compute the value in Java and pass it as a parameter");
            }
            TypeResolver.PropertyRead read = typeResolver.getterFor(bean, path.get(1), method);
            return new Resolved(paramName + "." + read.accessorCall(), read.type(), param, false,
                    typeResolver.handlerSiteOn(bean, path.get(1)));
        }

        private TypeElement beanOf(TypeMirror type, VariableElement param, String where) {
            TypeElement bean = typeResolver.typeElementOf(type);
            String fqn = bean == null ? "" : bean.getQualifiedName().toString();
            if (bean == null || fqn.equals("java.lang.Object")
                    || fqn.equals("java.util.Map") || fqn.equals("java.util.HashMap")) {
                throw new LarkBatisProcessingException(param, where
                        + ": parameter type " + type + " is not bindable. Typed DTOs or scalar"
                        + " parameters are required; Object/Map parameters were dropped");
            }
            return bean;
        }

        private void requireSingleSegment(List<String> path, String where, String paramName) {
            if (path.size() > 1) {
                throw new LarkBatisProcessingException(method, where
                        + ": " + paramName + " has no properties to navigate");
            }
        }

        private void requireNamed(String actual, String expected, String where, VariableElement param) {
            if (!actual.equals(expected)) {
                throw new LarkBatisProcessingException(param, where
                        + " does not match the parameter name \"" + expected
                        + "\" (LarkBatis resolves names at build time; rename one side)"
                        + syntheticNamesHint());
            }
        }

        /**
         * Gradle incremental builds hand aggregating processors UNCHANGED
         * mappers as class files, where parameter names are only present when
         * compiled with -parameters. Spell the fix out — "arg0" alone is a
         * terrible error message.
         */
        private String syntheticNamesHint() {
            boolean synthetic = params.keySet().stream().allMatch(n -> n.matches("arg\\d+"));
            return !params.isEmpty() && synthetic
                    ? ". Parameter names came back as argN: this mapper was read from a class"
                            + " file without -parameters (typical for Gradle incremental builds)."
                            + " Compile with -parameters, or name parameters with @Param."
                    : "";
        }

        /** paramN aliases, matching ParamNameResolver's generic names. */
        private int genericIndexOf(String name) {
            if (!name.startsWith("param")) {
                return -1;
            }
            try {
                return Integer.parseInt(name.substring("param".length()));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    // --- return shape -----------------------------------------------------------

    private record Return(ReturnShape shape, String returnTypeFqn, ValueKind scalarKind,
                          String scalarEnumType, String resultFqn, ReaderAccess readerAccess,
                          NestedResult nested) {
    }

    private Return returnOf(ExecutableElement method, StatementKind kind, List<SqlPiece> pieces,
            String statementId, MapperXmlParser.XmlResultMap resultMap) {
        TypeMirror returnType = method.getReturnType();
        String returnTypeFqn = returnType.toString();

        if (kind != StatementKind.SELECT) {
            return switch (returnTypeFqn) {
                case "int", "long", "boolean", "void",
                     "java.lang.Integer", "java.lang.Long", "java.lang.Boolean" ->
                        new Return(ReturnShape.UPDATE_COUNT, returnTypeFqn, null, null,
                                null, null, null);
                default -> throw new LarkBatisProcessingException(method,
                        kind + " must return int, long, boolean or void, not " + returnTypeFqn);
            };
        }

        if (returnType.getKind() == TypeKind.VOID) {
            throw new LarkBatisProcessingException(method, "SELECT must return something");
        }
        TypeMirror element = typeResolver.listElementOf(returnType);
        ReturnShape shape = ReturnShape.ONE;
        if (element != null) {
            shape = ReturnShape.MANY;
        } else {
            element = typeResolver.streamElementOf(returnType);
            if (element != null) {
                shape = ReturnShape.STREAM;
            }
        }
        TypeMirror resultType = element != null ? element : returnType;

        ValueKind scalarKind = typeResolver.valueKindOf(resultType);
        if (scalarKind != null) {
            if (resultMap != null) {
                throw new LarkBatisProcessingException(method, statementId
                        + ": resultMap=\"" + resultMap.id() + "\" on a method returning "
                        + resultType + " — a result map maps columns onto a bean's setters");
            }
            return new Return(shape, returnTypeFqn, scalarKind,
                    typeResolver.enumTypeOf(resultType), null, null, null);
        }

        TypeElement bean = typeResolver.typeElementOf(resultType);
        if (bean == null) {
            throw new LarkBatisProcessingException(method,
                    "Unsupported result type " + resultType);
        }
        Map<String, String> xmlHandlers = resultMap == null
                ? Map.of()
                : declaredHandlers(resultMap.mappings());
        ResultModel result = resultModels.computeIfAbsent(bean.getQualifiedName().toString(),
                fqn -> typeResolver.resultModelOf(bean, xmlHandlers));
        if (resultMap != null) {
            return resultMapReturn(method, shape, returnTypeFqn, result, pieces, statementId,
                    resultMap);
        }
        ReaderAccess access = readerAccessOf(method, pieces, result, statementId);
        return new Return(shape, returnTypeFqn, null, null, result.fqn(), access, null);
    }

    /**
     * Under {@link ColumnNaming#EXACT}, the select-list columns that would have
     * reached a property under the other convention and now reach none.
     *
     * <p>Silence is what MyBatis does here, and it is the one part of that
     * behaviour worth improving on: the column is dropped, the property keeps
     * its default, and nothing anywhere says so — the failure arrives as a null
     * in production. Only reachable when the build asked for this mode, so no
     * existing build is made noisier by it.
     */
    private void reportColumnsLostToExactNaming(ExecutableElement method, String statementId,
            ResultModel result, List<String> names, List<Integer> order) {
        if (columnNaming != ColumnNaming.EXACT) {
            return;
        }
        List<String> lost = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (order.contains(i + 1)) {
                continue; // this column reached a property
            }
            String loose = ColumnNaming.UNDERSCORE_TO_CAMEL_CASE.keyOf(names.get(i));
            for (PropertyModel property : result.properties()) {
                if (property.matchKey(ColumnNaming.UNDERSCORE_TO_CAMEL_CASE).equals(loose)) {
                    lost.add(names.get(i) + " → " + property.name());
                    break;
                }
            }
        }
        if (lost.isEmpty()) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.NOTE, statementId
                + ": mapUnderscoreToCamelCase is off, so these columns reach no property and"
                + " their properties keep their defaults — " + String.join(", ", lost)
                + ". Alias the column in the SQL, or name it with @Column.", method);
    }

    private ReaderAccess readerAccessOf(ExecutableElement method, List<SqlPiece> pieces,
            ResultModel result, String statementId) {
        SelectListParser.Result parsed = SelectListParser.parse(pieces);
        if (parsed instanceof SelectListParser.Result.Unparseable u) {
            // A truncated select list makes the parser report "no FROM"; name
            // the real cause instead (the downgrade has to be
            // reported, and a wrong reason is worse than none).
            String reason = pieces.stream().anyMatch(p -> p instanceof SqlPiece.Foreach)
                    ? FOREACH_IN_SELECT_LIST
                    : u.reason();
            // one statement is downgraded, not the whole mapper — and this NOTE
            // is the only thing that tells the author which one
            messager.printMessage(Diagnostic.Kind.NOTE, statementId
                    + ": name-based row reader (indexes resolved once from ResultSetMetaData"
                    + " on the first row) — " + reason, method);
            return ReaderAccess.nameBased(reason);
        }
        List<String> names = ((SelectListParser.Result.Columns) parsed).names();
        List<PropertyModel> properties = result.properties();
        List<Integer> order = new ArrayList<>(java.util.Collections.nCopies(properties.size(), 0));
        int matched = 0;
        for (int i = 0; i < names.size(); i++) {
            String key = columnNaming.keyOf(names.get(i));
            for (int k = 0; k < properties.size(); k++) {
                if (order.get(k) == 0 && properties.get(k).matchKey(columnNaming).equals(key)) {
                    order.set(k, i + 1);
                    matched++;
                    break;
                }
            }
        }
        if (matched == 0) {
            throw new LarkBatisProcessingException(method,
                    "No select-list column matches any property of " + result.fqn()
                            + " (columns: " + String.join(", ", names) + ")");
        }
        reportColumnsLostToExactNaming(method, statementId, result, names, order);
        boolean canonical = names.size() == properties.size();
        if (canonical) {
            for (int k = 0; k < properties.size(); k++) {
                if (order.get(k) != k + 1) {
                    canonical = false;
                    break;
                }
            }
        }
        return canonical ? ReaderAccess.canonical() : ReaderAccess.custom(List.copyOf(order));
    }

    // --- <resultMap> -----------------------------------------

    /**
     * A statement with {@code resultMap="..."}: the columns are declared, not
     * inferred from property names, and one level of nesting may be filled
     * from the same join.
     *
     * <p>No auto-mapping. MyBatis would additionally map every unmentioned
     * column whose name happens to match a property; a result map here maps
     * exactly what it declares. Same reason {@code test="count"} is a compile
     * error: a mapper that says what it means beats one that is forgiving.
     */
    private Return resultMapReturn(ExecutableElement method, ReturnShape shape,
            String returnTypeFqn, ResultModel result, List<SqlPiece> pieces, String statementId,
            MapperXmlParser.XmlResultMap resultMap) {
        String declared = resolveTypeName(method, statementId, resultMap.type(),
                "<resultMap id=\"" + resultMap.id() + "\">");
        if (!declared.equals(result.fqn())) {
            throw new LarkBatisProcessingException(method, statementId + ": <resultMap id=\""
                    + resultMap.id() + "\" type=\"" + resultMap.type() + "\"> resolves to "
                    + declared + ", but the method signature implies " + result.fqn());
        }

        SelectListParser.Result parsed = SelectListParser.parse(pieces);
        String where = "<resultMap id=\"" + resultMap.id() + "\">";
        result = withXmlHandlers(method, statementId, where,
                elements.getTypeElement(result.fqn()), result, resultMap.mappings());
        ReaderAccess access = mappedAccess(method, statementId, where, parsed, result,
                resultMap.mappings());

        MapperXmlParser.XmlNested xml = resultMap.nested();
        if (xml == null) {
            return new Return(shape, returnTypeFqn, null, null, result.fqn(), access, null);
        }

        String nestedWhere = where + " <" + (xml.collection() ? "collection" : "association")
                + " property=\"" + xml.property() + "\">";
        String childFqn = resolveTypeName(method, statementId, xml.type(), nestedWhere);
        TypeElement childElement = elements.getTypeElement(childFqn);
        Map<String, String> childHandlers = declaredHandlers(xml.mappings());
        ResultModel child = resultModels.computeIfAbsent(childFqn,
                fqn -> typeResolver.resultModelOf(childElement, childHandlers));
        child = withXmlHandlers(method, statementId, nestedWhere, childElement, child,
                xml.mappings());
        ReaderAccess childAccess =
                mappedAccess(method, statementId, nestedWhere, parsed, child, xml.mappings());

        ExecutableElement setter = typeResolver.setterOf(
                elements.getTypeElement(result.fqn()), xml.property());
        if (setter == null) {
            throw new LarkBatisProcessingException(method, nestedWhere + ": " + result.fqn()
                    + " has no setter for property \"" + xml.property() + "\"");
        }
        TypeMirror target = setter.getParameters().get(0).asType();
        if (xml.collection()) {
            TypeMirror element = typeResolver.listElementOf(target);
            if (element == null || !element.toString().equals(childFqn)) {
                throw new LarkBatisProcessingException(method, nestedWhere + ": "
                        + setter.getSimpleName() + " takes " + target + ", expected"
                        + " java.util.List<" + childFqn + ">");
            }
        } else if (!target.toString().equals(childFqn)) {
            throw new LarkBatisProcessingException(method, nestedWhere + ": "
                    + setter.getSimpleName() + " takes " + target + ", expected " + childFqn);
        }

        if (shape == ReturnShape.STREAM) {
            // the grouping loop needs the row after the current one to know
            // whether the parent is finished; a cursor handed out one row at a
            // time cannot answer that without buffering the whole thing, which
            // is what a Stream return exists to avoid
            throw new LarkBatisProcessingException(method, statementId + ": " + nestedWhere
                    + " cannot be combined with a Stream return — a parent spans several rows,"
                    + " so it is only complete once the next parent starts. Return a List, or"
                    + " drop the nesting and stream the flat rows");
        }
        noteOrderingRequirement(method, statementId, pieces, nestedWhere);
        return new Return(shape, returnTypeFqn, null, null, result.fqn(), access,
                new NestedResult(
                        xml.collection() ? NestedResult.Kind.COLLECTION
                                : NestedResult.Kind.ASSOCIATION,
                        xml.property(), setter.getSimpleName().toString(), childFqn, childAccess,
                        keyProperties(method, statementId, where, result, resultMap.mappings()),
                        keyProperties(method, statementId, nestedWhere, child, xml.mappings())
                                .get(0)));
    }

    /**
     * The one thing about a nested result map that cannot be checked, only
     * said out loud: the grouping loop starts a new parent where the key
     * changes, so a ResultSet that revisits a key produces duplicate parents
     * rather than merging them. MyBatis keeps a map of parent keys and merges
     * regardless of order; LarkBatis trades that map for the ORDER BY.
     *
     * <p>A note rather than a warning, and only when there is no ORDER BY at
     * all: matching the ordering terms against the declared column names is
     * exactly the comparison that fails on {@code ORDER BY t.id} versus
     * {@code column="t_id"}, and a check that fires on correct mappers is
     * worse than no check.
     */
    private void noteOrderingRequirement(ExecutableElement method, String statementId,
            List<SqlPiece> pieces, String where) {
        StringBuilder text = new StringBuilder();
        for (SqlPiece piece : pieces) {
            if (piece instanceof SqlPiece.Text t) {
                text.append(t.sql());
            }
        }
        if (text.toString().toLowerCase(java.util.Locale.ROOT).contains("order by")) {
            return;
        }
        messager.printMessage(Diagnostic.Kind.NOTE, statementId + ": " + where
                + " groups rows by the parent key and the statement has no ORDER BY."
                + " Rows that revisit a parent key after another parent's rows become a"
                + " second parent object — add ORDER BY on the <id> column, or ignore this"
                + " if the query returns one parent.", method);
    }

    /**
     * Column positions for one declared mapping list. Positional when the
     * select list parsed, name-based against the declared column names when it
     * did not — the same downgrade as everywhere else, only matching on the
     * the map named instead of on the property's own name.
     */
    /** Everything wrong with {@code -Alarkbatis.typeHandlers}, one message each. */
    public List<String> typeHandlerDefaultProblems() {
        return typeResolver.typeHandlerDefaultProblems();
    }

    /** Registered types that moved nothing in this compilation. */
    public List<String> unusedTypeHandlerDefaults() {
        return typeResolver.unusedTypeHandlerDefaults();
    }

    /** Property name to handler FQN, for the mappings that name one. */
    private static Map<String, String> declaredHandlers(
            List<MapperXmlParser.XmlMapping> mappings) {
        Map<String, String> handlers = new LinkedHashMap<>();
        for (MapperXmlParser.XmlMapping mapping : mappings) {
            if (mapping.handler() != null) {
                handlers.put(mapping.property(), mapping.handler());
            }
        }
        return handlers;
    }

    /**
     * Folds the handlers a {@code <resultMap>} names onto the result class's
     * properties, and registers the result so the emitted reader uses them.
     *
     * <p>MyBatis resolves a {@code typeHandler} per mapping, so one class can be
     * read two ways by two statements. Here there is one generated reader per
     * result class, so a property has one handler: a second statement naming a
     * different one for the same property is a build error rather than a reader
     * that quietly serves whichever statement was compiled first. Two result
     * classes is the way to say two readings.
     */
    private ResultModel withXmlHandlers(ExecutableElement method, String statementId, String where,
            TypeElement bean, ResultModel result, List<MapperXmlParser.XmlMapping> mappings) {
        if (mappings.stream().noneMatch(mapping -> mapping.handler() != null)) {
            return result;
        }
        List<PropertyModel> properties = new ArrayList<>(result.properties());
        for (MapperXmlParser.XmlMapping mapping : mappings) {
            if (mapping.handler() == null) {
                continue;
            }
            int index = indexOfProperty(properties, mapping.property());
            if (index < 0) {
                // mappedAccess names this one properly a moment later
                continue;
            }
            PropertyModel property = properties.get(index);
            String site = statementId + ": " + where + " typeHandler on property \""
                    + mapping.property() + "\"";
            TypeElement declared = elements.getTypeElement(mapping.handler());
            if (declared == null) {
                throw new LarkBatisProcessingException(method, site + " names \""
                        + mapping.handler() + "\", which is not on the compilation classpath"
                        + (mapping.handler().indexOf('.') < 0
                                ? " — type aliases are resolved at build time, so write the"
                                        + " fully-qualified class name"
                                : ""));
            }
            String fqn = typeResolver.validateHandler(method, declared.asType(),
                    typeResolver.setterFor(bean, mapping.property(), method).type(), site);
            if (property.handler() != null && !property.handler().equals(fqn)) {
                throw new LarkBatisProcessingException(method, site + ": property \""
                        + mapping.property() + "\" of " + result.fqn() + " already reads through "
                        + property.handler() + ". One reader is generated per result class, so a"
                        + " property has one handler — give the second reading a result class of"
                        + " its own");
            }
            properties.set(index, new PropertyModel(property.name(), property.setterName(),
                    property.kind(), property.enumType(), property.column(), fqn));
        }
        ResultModel merged = new ResultModel(result.fqn(), result.packageName(),
                result.simpleName(), List.copyOf(properties));
        resultModels.put(result.fqn(), merged);
        return merged;
    }

    private ReaderAccess mappedAccess(ExecutableElement method, String statementId, String where,
            SelectListParser.Result parsed, ResultModel result,
            List<MapperXmlParser.XmlMapping> mappings) {
        List<PropertyModel> properties = result.properties();
        String[] columnByProperty = new String[properties.size()];
        for (MapperXmlParser.XmlMapping mapping : mappings) {
            int index = indexOfProperty(properties, mapping.property());
            if (index < 0) {
                ExecutableElement setter = typeResolver.setterOf(
                        elements.getTypeElement(result.fqn()), mapping.property());
                throw new LarkBatisProcessingException(method, statementId + ": " + where
                        + " maps property \"" + mapping.property() + "\", which "
                        + result.fqn() + (setter == null
                                ? " has no setter for"
                                : " sets from " + setter.getParameters().get(0).asType()
                                        + " — not a column type. A bean or a List of one is"
                                        + " filled by <association>/<collection>, not <result>"));
            }
            if (columnByProperty[index] != null) {
                throw new LarkBatisProcessingException(method, statementId + ": " + where
                        + " maps property \"" + mapping.property() + "\" twice");
            }
            columnByProperty[index] = mapping.column();
        }

        if (parsed instanceof SelectListParser.Result.Unparseable unparseable) {
            messager.printMessage(Diagnostic.Kind.NOTE, statementId
                    + ": name-based row reader for " + where + " (column positions resolved once"
                    + " from ResultSetMetaData on the first row) — " + unparseable.reason(),
                    method);
            List<String> names = new ArrayList<>();
            for (String column : columnByProperty) {
                names.add(column);
            }
            return ReaderAccess.nameBasedMapped(names, unparseable.reason());
        }

        List<String> selectList = ((SelectListParser.Result.Columns) parsed).names();
        List<Integer> order = new ArrayList<>(java.util.Collections.nCopies(properties.size(), 0));
        for (int k = 0; k < columnByProperty.length; k++) {
            if (columnByProperty[k] == null) {
                continue;
            }
            String propertyName = properties.get(k).name();
            int position = positionOf(selectList, columnByProperty[k]);
            if (position == 0) {
                boolean isKey = mappings.stream()
                        .anyMatch(m -> m.id() && m.property().equals(propertyName));
                String message = statementId + ": " + where + " maps property \""
                        + propertyName + "\" to column \"" + columnByProperty[k]
                        + "\", which the select list does not contain (it has: "
                        + String.join(", ", selectList) + ")";
                if (isKey) {
                    // an <id> column is what the grouping loop reads; without it
                    // there is no loop to generate
                    throw new LarkBatisProcessingException(method, message);
                }
                messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                        message + " — the property stays unset", method);
                continue;
            }
            if (position < 0) {
                // first occurrence wins, as in MyBatis (ResultSetWrapper looks
                // the column name up with indexOf), but a join that aliases
                // nothing is a bug waiting for the wrong half of the row
                messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, statementId + ": "
                        + where + " maps to column \"" + columnByProperty[k] + "\", which the"
                        + " select list contains more than once; the first one wins. Alias the"
                        + " columns so each has its own name.", method);
                position = -position;
            }
            order.set(k, position);
        }
        boolean canonical = selectList.size() == properties.size();
        for (int k = 0; canonical && k < properties.size(); k++) {
            canonical = order.get(k) == k + 1;
        }
        return canonical ? ReaderAccess.canonical() : ReaderAccess.custom(List.copyOf(order));
    }

    /**
     * 1-based position of a column in the select list, 0 when absent, and the
     * negated position when the name appears more than once.
     */
    private int positionOf(List<String> selectList, String column) {
        String key = columnNaming.keyOf(column);
        int found = 0;
        int hits = 0;
        for (int i = 0; i < selectList.size(); i++) {
            if (columnNaming.keyOf(selectList.get(i)).equals(key)) {
                hits++;
                if (found == 0) {
                    found = i + 1;
                }
            }
        }
        return hits > 1 ? -found : found;
    }

    /** The {@code <id>} properties of one mapping list, in declaration order. */
    private List<NestedResult.KeyProperty> keyProperties(ExecutableElement method,
            String statementId, String where, ResultModel result,
            List<MapperXmlParser.XmlMapping> mappings) {
        List<NestedResult.KeyProperty> keys = new ArrayList<>();
        for (MapperXmlParser.XmlMapping mapping : mappings) {
            if (!mapping.id()) {
                continue;
            }
            int index = indexOfProperty(result.properties(), mapping.property());
            PropertyModel property = result.properties().get(index);
            if (property.kind() == ValueKind.BYTES) {
                throw new LarkBatisProcessingException(method, statementId + ": " + where
                        + " uses byte[] property \"" + mapping.property() + "\" as an <id>;"
                        + " array identity is not row identity — pick a scalar key column");
            }
            if (property.handler() != null) {
                // the grouping loop reads each key into a typed local and
                // compares it row to row; that local's type would be the
                // handler's, which the IR does not carry. Refused rather than
                // read through the wrong codec.
                throw new LarkBatisProcessingException(method, statementId + ": " + where
                        + " uses property \"" + mapping.property() + "\", which has a @Handler,"
                        + " as an <id>. A grouping key is read into a typed local and compared"
                        + " row to row — map the join on a key column the built-in codec reads");
            }
            keys.add(new NestedResult.KeyProperty(index, property.kind(), property.enumType()));
        }
        return keys;
    }

    private static int indexOfProperty(List<PropertyModel> properties, String name) {
        for (int k = 0; k < properties.size(); k++) {
            if (properties.get(k).name().equals(name)) {
                return k;
            }
        }
        return -1;
    }

    /**
     * A declared type name from mapper XML. Type aliases were resolved away at
     * build time, so this is a fully-qualified name or a
     * compile error saying so.
     */
    private String resolveTypeName(ExecutableElement method, String statementId, String name,
            String where) {
        TypeElement element = elements.getTypeElement(name);
        if (element != null) {
            return element.getQualifiedName().toString();
        }
        throw new LarkBatisProcessingException(method, statementId + ": " + where
                + " names type \"" + name + "\", which is not on the compilation classpath"
                + (name.indexOf('.') < 0
                        ? " — type aliases are resolved at build time,"
                                + " so write the fully-qualified class name"
                        : ""));
    }

    // --- generated keys ---------------------------------------------

    private KeyModel keysOf(ExecutableElement method, Sql sql,
            LinkedHashMap<String, VariableElement> params, boolean hasParamAnnotation,
            StatementModel.Batch batch, TypeElement batchElementType, boolean hasForeach) {
        Options options = method.getAnnotation(Options.class);
        String keyProperty;
        String keyColumn;
        if (sql.xml != null) {
            if (options != null) {
                throw new LarkBatisProcessingException(method, "Method "
                        + method.getSimpleName() + " takes its SQL from XML; configure generated"
                        + " keys on the XML statement, not with @Options");
            }
            if (!sql.xml.useGeneratedKeys()) {
                return null;
            }
            keyProperty = sql.xml.keyProperty();
            keyColumn = sql.xml.keyColumn();
        } else {
            if (options == null || !options.useGeneratedKeys()) {
                return null;
            }
            keyProperty = options.keyProperty();
            keyColumn = options.keyColumn();
        }
        if (sql.kind != StatementKind.INSERT) {
            throw new LarkBatisProcessingException(method,
                    "useGeneratedKeys only applies to @Insert");
        }
        if (hasForeach) {
            // The keys come back one per row, so assigning them means walking
            // the loop's collection again — the batch form already does that,
            // and guessing here would silently leave ids null.
            throw new LarkBatisProcessingException(method,
                    "useGeneratedKeys is not supported on a <foreach> insert yet: pass a"
                            + " List<Bean> parameter and drop the <foreach>, and the generated"
                            + " batch insert assigns a key to every element");
        }
        if (keyProperty.isEmpty()) {
            throw new LarkBatisProcessingException(method,
                    "useGeneratedKeys needs keyProperty (where should the key go?)");
        }
        List<String> keyProperties = List.of(keyProperty.split(","));
        List<String> keyColumns = keyColumn.isEmpty()
                ? List.of()
                : List.of(keyColumn.split(","));
        if (!keyColumns.isEmpty() && keyColumns.size() != keyProperties.size()) {
            throw new LarkBatisProcessingException(method,
                    "keyColumn lists " + keyColumns.size() + " columns but keyProperty lists "
                            + keyProperties.size() + " properties");
        }
        if (keyColumns.isEmpty()) {
            // Without explicit key columns, Oracle returns ROWID and PostgreSQL
            // returns every column — warn while the IDE is still open
            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                    "useGeneratedKeys without keyColumn falls back to RETURN_GENERATED_KEYS,"
                            + " which returns ROWID on Oracle and all columns on PostgreSQL."
                            + " Name the key column(s) explicitly.", method);
        }

        List<KeyModel.Assignment> assignments = new ArrayList<>();
        for (String rawProperty : keyProperties) {
            String propertyPath = rawProperty.trim();
            List<String> path = List.of(propertyPath.split("\\.", -1));
            TypeElement bean;
            String target;
            if (batch != null) {
                if (path.size() != 1) {
                    throw new LarkBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\": batch keys assign element"
                                    + " properties directly (one level)");
                }
                bean = batchElementType;
                target = batch.paramName() + ".get(i)";
            } else if (params.size() == 1 && !hasParamAnnotation && path.size() == 1) {
                Map.Entry<String, VariableElement> sole = params.entrySet().iterator().next();
                bean = typeResolver.typeElementOf(sole.getValue().asType());
                if (bean == null || typeResolver.valueKindOf(sole.getValue().asType()) != null) {
                    throw new LarkBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\" needs a bean parameter to land on");
                }
                target = sole.getKey();
            } else if (path.size() == 2) {
                VariableElement param = params.get(path.get(0));
                if (param == null) {
                    // the MyBatis runtime ExecutorException, moved to compile time
                    throw new LarkBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\": no parameter named \""
                                    + path.get(0) + "\". With multiple parameters, keyProperty"
                                    + " must include the parameter name (e.g. \"u.id\")."
                                    + " Available: " + String.join(", ", params.keySet()));
                }
                bean = typeResolver.typeElementOf(param.asType());
                if (bean == null) {
                    throw new LarkBatisProcessingException(param,
                            "keyProperty \"" + propertyPath + "\": parameter " + path.get(0)
                                    + " is not a bean");
                }
                target = path.get(0);
                path = path.subList(1, 2);
            } else {
                throw new LarkBatisProcessingException(method,
                        "keyProperty \"" + propertyPath + "\" does not resolve; use \"property\""
                                + " (single bean parameter) or \"param.property\"");
            }
            Element handlerSite = typeResolver.handlerSiteOn(bean, path.get(0));
            if (handlerSite != null) {
                // getGeneratedKeys() hands back whatever the driver made of the
                // key, and the assignment below is the only read of it. Honouring
                // a handler here would mean widening keyReadable past what the
                // key-count check can verify, so it is refused instead.
                throw new LarkBatisProcessingException(handlerSite,
                        "keyProperty \"" + propertyPath + "\" has a @Handler. Generated keys are"
                                + " read straight from getGeneratedKeys() with no handler step —"
                                + " assign the raw key and convert it in the caller");
            }
            TypeResolver.PropertyWrite write = typeResolver.setterFor(bean, path.get(0), method);
            ValueKind keyKind = typeResolver.valueKindOf(write.type());
            if (keyKind == null || !keyReadable(keyKind)) {
                throw new LarkBatisProcessingException(method,
                        "keyProperty \"" + propertyPath + "\" has type " + write.type()
                                + "; generated keys support integral types, String and BigDecimal");
            }
            assignments.add(new KeyModel.Assignment(target, write.setterName(), keyKind));
        }
        return new KeyModel(
                keyColumns.stream().map(String::trim).toList(),
                List.copyOf(assignments));
    }

    private static boolean keyReadable(ValueKind kind) {
        return switch (kind) {
            case PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG,
                 BOX_BYTE, BOX_SHORT, BOX_INT, BOX_LONG,
                 STRING, BIG_DECIMAL -> true;
            default -> false;
        };
    }

    private void error(javax.lang.model.element.Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
