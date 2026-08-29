package io.github.lightbatis.processor.frontend;

import io.github.lightbatis.annotations.Delete;
import io.github.lightbatis.annotations.Insert;
import io.github.lightbatis.annotations.Options;
import io.github.lightbatis.annotations.OrderBy;
import io.github.lightbatis.annotations.Param;
import io.github.lightbatis.annotations.Select;
import io.github.lightbatis.annotations.Update;
import io.github.lightbatis.processor.ir.KeyModel;
import io.github.lightbatis.processor.ir.MapperModel;
import io.github.lightbatis.processor.ir.ParamModel;
import io.github.lightbatis.processor.ir.PropertyModel;
import io.github.lightbatis.processor.ir.ReaderAccess;
import io.github.lightbatis.processor.ir.ResultModel;
import io.github.lightbatis.processor.ir.ReturnShape;
import io.github.lightbatis.processor.ir.SqlPiece;
import io.github.lightbatis.processor.ir.StatementKind;
import io.github.lightbatis.processor.ir.StatementModel;
import io.github.lightbatis.processor.ir.ValueKind;
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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Annotation frontend: mapper interface → {@link MapperModel} IR (build plan
 * §05, task 6). The XML frontend of M2 produces the same IR — one IR, two
 * parsers.
 *
 * <p>All shape validation happens here; every rejection is a compile error on
 * the precise element. Emitters can assume a valid model.
 */
public final class AnnotationFrontend {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Identifiers the generated bodies use; loop variables must dodge them. */
    private static final Set<String> RESERVED_LOCALS =
            Set.of("s", "c", "ps", "rs", "gk", "n", "i", "out", "cols", "sql");

    private final TypeResolver typeResolver;
    private final Messager messager;
    /** Shared across mappers: one reader per result class (design red line). */
    private final Map<String, ResultModel> resultModels;

    public AnnotationFrontend(ProcessingEnvironment env, Map<String, ResultModel> resultModels) {
        this.typeResolver = new TypeResolver(env.getElementUtils(), env.getTypeUtils());
        this.messager = env.getMessager();
        this.resultModels = resultModels;
    }

    /**
     * Parses one mapper interface. Reports every error it finds and returns
     * null if there was any — a mapper with errors is never emitted, partial
     * code is worse than no code.
     */
    public MapperModel parse(TypeElement mapper) {
        boolean failed = false;
        if (mapper.getKind() != ElementKind.INTERFACE) {
            error(mapper, "LightBatis statements belong on interface methods; "
                    + mapper.getQualifiedName() + " is not an interface");
            return null;
        }
        if (mapper.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(mapper, "Mapper " + mapper.getQualifiedName()
                    + " must be a top-level interface (M1 limitation)");
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
                continue; // default methods are the escape hatch (design §09)
            }
            if (!methodNames.add(method.getSimpleName().toString())) {
                error(method, "Overloaded mapper methods are not supported: "
                        + method.getSimpleName() + " (constant names would collide)");
                failed = true;
                continue;
            }
            try {
                statements.add(parseStatement(interfaceFqn, method));
            } catch (LightBatisProcessingException e) {
                error(e.element() != null ? e.element() : method, e.getMessage());
                failed = true;
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

    private StatementModel parseStatement(String interfaceFqn, ExecutableElement method) {
        Sql sql = sqlOf(method);
        String methodName = method.getSimpleName().toString();
        String statementId = interfaceFqn + "." + methodName;

        // parameters, in order, named from @Param or the AST
        List<VariableElement> paramElements = new ArrayList<>(method.getParameters());
        LinkedHashMap<String, VariableElement> paramsByName = new LinkedHashMap<>();
        for (VariableElement param : paramElements) {
            Param annotation = param.getAnnotation(Param.class);
            String name = annotation != null ? annotation.value() : param.getSimpleName().toString();
            if (RESERVED_LOCALS.contains(name)) {
                throw new LightBatisProcessingException(param, "Parameter name \"" + name
                        + "\" collides with a local of the generated body; rename it or add @Param");
            }
            if (paramsByName.putIfAbsent(name, param) != null) {
                throw new LightBatisProcessingException(param, "Duplicate parameter name: " + name);
            }
        }
        boolean hasParamAnnotation = paramElements.stream()
                .anyMatch(p -> p.getAnnotation(Param.class) != null);

        // batch: DML whose single parameter is a List<Bean> (design §07 case 2)
        StatementModel.Batch batch = null;
        TypeElement batchElementType = null;
        if (sql.kind != StatementKind.SELECT && paramElements.size() == 1) {
            TypeMirror element = typeResolver.listElementOf(paramElements.get(0).asType());
            if (element != null) {
                batchElementType = typeResolver.typeElementOf(element);
                if (batchElementType == null || typeResolver.valueKindOf(element) != null) {
                    throw new LightBatisProcessingException(paramElements.get(0),
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

        List<SqlPiece> pieces = new ArrayList<>();
        for (SqlTokenizer.RawToken token : SqlTokenizer.tokenize(sql.text)) {
            if (token instanceof SqlTokenizer.RawToken.Text t) {
                pieces.add(new SqlPiece.Text(t.text()));
            } else if (token instanceof SqlTokenizer.RawToken.Hash h) {
                pieces.add(resolver.bind(h.expression()));
            } else if (token instanceof SqlTokenizer.RawToken.Dollar d) {
                pieces.add(resolver.dollar(d.expression()));
            }
        }

        List<ParamModel> params = new ArrayList<>();
        paramsByName.forEach((name, element) -> params.add(new ParamModel(name, element.asType().toString())));

        Return ret = returnOf(method, sql.kind, pieces, statementId);
        KeyModel keys = keysOf(method, sql.kind, paramsByName, hasParamAnnotation, batch, batchElementType);

        return new StatementModel(methodName, statementId, sql.kind, List.copyOf(pieces),
                List.copyOf(params), ret.shape, ret.returnTypeFqn, ret.scalarKind, ret.scalarEnumType,
                ret.resultFqn, ret.readerAccess, keys, batch);
    }

    private record Sql(StatementKind kind, String text) {
    }

    private Sql sqlOf(ExecutableElement method) {
        List<Sql> found = new ArrayList<>();
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            found.add(new Sql(StatementKind.SELECT, joinSql(select.value())));
        }
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) {
            found.add(new Sql(StatementKind.INSERT, joinSql(insert.value())));
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null) {
            found.add(new Sql(StatementKind.UPDATE, joinSql(update.value())));
        }
        Delete delete = method.getAnnotation(Delete.class);
        if (delete != null) {
            found.add(new Sql(StatementKind.DELETE, joinSql(delete.value())));
        }
        if (found.size() != 1) {
            throw new LightBatisProcessingException(method, found.isEmpty()
                    ? "Abstract mapper method without a statement annotation; "
                            + "hand-written logic belongs in a default method (design §09)"
                    : "Exactly one of @Select/@Insert/@Update/@Delete is allowed");
        }
        if (found.get(0).text.isEmpty()) {
            throw new LightBatisProcessingException(method, "Empty SQL");
        }
        return found.get(0);
    }

    /** MyBatis joins annotation SQL with a single space (MapperAnnotationBuilder.java:658). */
    private static String joinSql(String[] values) {
        return String.join(" ", values).trim();
    }

    // --- #{} / ${} resolution ----------------------------------------------------

    /** A resolved value expression: how generated code reads it, and its type. */
    private record Resolved(String accessor, TypeMirror type, VariableElement rootParam,
                            boolean isFragment) {
    }

    private final class BindResolver {
        private final ExecutableElement method;
        private final LinkedHashMap<String, VariableElement> params;
        private final boolean hasParamAnnotation;
        private final StatementModel.Batch batch;
        private final TypeElement batchElementType;

        BindResolver(ExecutableElement method, LinkedHashMap<String, VariableElement> params,
                boolean hasParamAnnotation, StatementModel.Batch batch, TypeElement batchElementType) {
            this.method = method;
            this.params = params;
            this.hasParamAnnotation = hasParamAnnotation;
            this.batch = batch;
            this.batchElementType = batchElementType;
        }

        SqlPiece.Bind bind(String expression) {
            // MyBatis allows #{prop, jdbcType=..., ...}; the type decisions those
            // attributes served are made at build time here, so only the property
            // part matters. Attributes are accepted and ignored.
            String property = expression.split(",", 2)[0].trim();
            Resolved value = resolve(property, expression, false);
            if (value.isFragment()) {
                throw new LightBatisProcessingException(value.rootParam(),
                        "#{" + expression + "}: SqlFragment splices via ${}, not #{}");
            }
            ValueKind kind = typeResolver.valueKindOf(value.type());
            if (kind == null) {
                throw new LightBatisProcessingException(method,
                        "#{" + expression + "} has unsupported type " + value.type()
                                + ". Supported: primitives and their boxes, String, BigDecimal,"
                                + " byte[], LocalDate/LocalTime/LocalDateTime/Instant, enums.");
            }
            return new SqlPiece.Bind(expression, value.accessor(), kind,
                    typeResolver.enumTypeOf(value.type()));
        }

        SqlPiece.Dollar dollar(String expression) {
            String property = expression.trim();
            Resolved value = resolve(property, expression, true);
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
                throw new LightBatisProcessingException(
                        value.rootParam() != null ? value.rootParam() : method,
                        "${" + expression + "} would splice a String into SQL. Use SqlFragment"
                                + " (audited at the call site) or @OrderBy(allowed = {...})"
                                + " on the parameter (design §08).");
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
                        throw new LightBatisProcessingException(method,
                                "${" + expression + "}: use the primitive type — a null "
                                        + value.type() + " would splice the text \"null\" into SQL");
                    default:
                        // fall through to the generic rejection below
                }
            }
            throw new LightBatisProcessingException(method,
                    "${" + expression + "} has type " + value.type()
                            + "; only SqlFragment, int/long/short/byte/boolean, enums,"
                            + " or @OrderBy String parameters may splice (design §08)");
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

        private Resolved resolve(String property, String rawExpression, boolean dollar) {
            String where = (dollar ? "${" : "#{") + rawExpression + "}";
            List<String> path = List.of(property.split("\\.", -1));
            if (path.isEmpty() || path.stream().anyMatch(seg -> !IDENTIFIER.matcher(seg).matches())) {
                throw new LightBatisProcessingException(method, where
                        + " is not a simple property path; OGNL expressions were dropped (design §08)");
            }

            if (batch != null) {
                // batch bodies bind against the loop element
                if (path.size() > 1) {
                    throw new LightBatisProcessingException(method, where
                            + ": batch statements bind element properties directly (one level)");
                }
                TypeResolver.PropertyRead read =
                        typeResolver.getterFor(batchElementType, path.get(0), method);
                return new Resolved(batch.loopVar() + "." + read.accessorCall(), read.type(),
                        params.values().iterator().next(), false);
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
                if (typeResolver.valueKindOf(paramType) != null) {
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
                            : " (only one property level is supported in M1)";
                    throw new LightBatisProcessingException(method, where + " does not resolve" + hint);
                }
                TypeResolver.PropertyRead read;
                try {
                    read = typeResolver.getterFor(bean, path.get(0), method);
                } catch (LightBatisProcessingException noProperty) {
                    if (path.get(0).equals(paramName)) {
                        // the user meant the parameter itself, and its type is
                        // outside the whitelist — say that, not "no property"
                        throw new LightBatisProcessingException(param, where
                                + " has unsupported type " + paramType
                                + ". Supported: primitives and their boxes, String, BigDecimal,"
                                + " byte[], LocalDate/LocalTime/LocalDateTime/Instant, enums,"
                                + " or a bean whose property you reference.");
                    }
                    throw noProperty;
                }
                return new Resolved(paramName + "." + read.accessorCall(), read.type(), param, false);
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
                throw new LightBatisProcessingException(method, where
                        + " does not match any parameter. Available: "
                        + String.join(", ", params.keySet()) + syntheticNamesHint());
            }
            TypeMirror paramType = param.asType();
            if (typeResolver.isSqlFragment(paramType)) {
                requireSingleSegment(path, where, paramName);
                return new Resolved(paramName, paramType, param, true);
            }
            if (typeResolver.valueKindOf(paramType) != null) {
                requireSingleSegment(path, where, paramName);
                return new Resolved(paramName, paramType, param, false);
            }
            TypeElement bean = beanOf(paramType, param, where);
            if (path.size() == 1) {
                throw new LightBatisProcessingException(method, where
                        + ": parameter " + paramName + " is a bean; bind one of its properties"
                        + " (e.g. " + (dollar ? "${" : "#{") + paramName + ".property})");
            }
            if (path.size() > 2) {
                throw new LightBatisProcessingException(method, where
                        + ": only one property level is supported in M1");
            }
            TypeResolver.PropertyRead read = typeResolver.getterFor(bean, path.get(1), method);
            return new Resolved(paramName + "." + read.accessorCall(), read.type(), param, false);
        }

        private TypeElement beanOf(TypeMirror type, VariableElement param, String where) {
            TypeElement bean = typeResolver.typeElementOf(type);
            String fqn = bean == null ? "" : bean.getQualifiedName().toString();
            if (bean == null || fqn.equals("java.lang.Object")
                    || fqn.equals("java.util.Map") || fqn.equals("java.util.HashMap")) {
                throw new LightBatisProcessingException(param, where
                        + ": parameter type " + type + " is not bindable. Typed DTOs or scalar"
                        + " parameters are required; Object/Map parameters were dropped (design §08)");
            }
            return bean;
        }

        private void requireSingleSegment(List<String> path, String where, String paramName) {
            if (path.size() > 1) {
                throw new LightBatisProcessingException(method, where
                        + ": " + paramName + " has no properties to navigate");
            }
        }

        private void requireNamed(String actual, String expected, String where, VariableElement param) {
            if (!actual.equals(expected)) {
                throw new LightBatisProcessingException(param, where
                        + " does not match the parameter name \"" + expected
                        + "\" (LightBatis resolves names at build time; rename one side)"
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
                          String scalarEnumType, String resultFqn, ReaderAccess readerAccess) {
    }

    private Return returnOf(ExecutableElement method, StatementKind kind, List<SqlPiece> pieces,
            String statementId) {
        TypeMirror returnType = method.getReturnType();
        String returnTypeFqn = returnType.toString();

        if (kind != StatementKind.SELECT) {
            return switch (returnTypeFqn) {
                case "int", "long", "boolean", "void",
                     "java.lang.Integer", "java.lang.Long", "java.lang.Boolean" ->
                        new Return(ReturnShape.UPDATE_COUNT, returnTypeFqn, null, null, null, null);
                default -> throw new LightBatisProcessingException(method,
                        kind + " must return int, long, boolean or void, not " + returnTypeFqn);
            };
        }

        if (returnType.getKind() == TypeKind.VOID) {
            throw new LightBatisProcessingException(method, "SELECT must return something");
        }
        TypeMirror element = typeResolver.listElementOf(returnType);
        ReturnShape shape = element != null ? ReturnShape.MANY : ReturnShape.ONE;
        TypeMirror resultType = element != null ? element : returnType;

        ValueKind scalarKind = typeResolver.valueKindOf(resultType);
        if (scalarKind != null) {
            return new Return(shape, returnTypeFqn, scalarKind,
                    typeResolver.enumTypeOf(resultType), null, null);
        }

        TypeElement bean = typeResolver.typeElementOf(resultType);
        if (bean == null) {
            throw new LightBatisProcessingException(method,
                    "Unsupported result type " + resultType);
        }
        ResultModel result = resultModels.computeIfAbsent(
                bean.getQualifiedName().toString(), fqn -> typeResolver.resultModelOf(bean));
        ReaderAccess access = readerAccessOf(method, pieces, result, statementId);
        return new Return(shape, returnTypeFqn, null, null, result.fqn(), access);
    }

    private ReaderAccess readerAccessOf(ExecutableElement method, List<SqlPiece> pieces,
            ResultModel result, String statementId) {
        SelectListParser.Result parsed = SelectListParser.parse(pieces);
        if (parsed instanceof SelectListParser.Result.Unparseable u) {
            // design §04/§08: downgrade this one statement and say so at build time
            messager.printMessage(Diagnostic.Kind.NOTE, statementId
                    + ": name-based row reader (indexes resolved once from ResultSetMetaData"
                    + " on the first row) — " + u.reason(), method);
            return ReaderAccess.nameBased(u.reason());
        }
        List<String> names = ((SelectListParser.Result.Columns) parsed).names();
        List<PropertyModel> properties = result.properties();
        List<Integer> order = new ArrayList<>(java.util.Collections.nCopies(properties.size(), 0));
        int matched = 0;
        for (int i = 0; i < names.size(); i++) {
            String key = PropertyModel.matchKeyOf(names.get(i));
            for (int k = 0; k < properties.size(); k++) {
                if (order.get(k) == 0 && properties.get(k).matchKey().equals(key)) {
                    order.set(k, i + 1);
                    matched++;
                    break;
                }
            }
        }
        if (matched == 0) {
            throw new LightBatisProcessingException(method,
                    "No select-list column matches any property of " + result.fqn()
                            + " (columns: " + String.join(", ", names) + ")");
        }
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

    // --- generated keys (design §07) ---------------------------------------------

    private KeyModel keysOf(ExecutableElement method, StatementKind kind,
            LinkedHashMap<String, VariableElement> params, boolean hasParamAnnotation,
            StatementModel.Batch batch, TypeElement batchElementType) {
        Options options = method.getAnnotation(Options.class);
        if (options == null || !options.useGeneratedKeys()) {
            return null;
        }
        if (kind != StatementKind.INSERT) {
            throw new LightBatisProcessingException(method,
                    "useGeneratedKeys only applies to @Insert");
        }
        if (options.keyProperty().isEmpty()) {
            throw new LightBatisProcessingException(method,
                    "useGeneratedKeys needs keyProperty (where should the key go?)");
        }
        List<String> keyProperties = List.of(options.keyProperty().split(","));
        List<String> keyColumns = options.keyColumn().isEmpty()
                ? List.of()
                : List.of(options.keyColumn().split(","));
        if (!keyColumns.isEmpty() && keyColumns.size() != keyProperties.size()) {
            throw new LightBatisProcessingException(method,
                    "keyColumn lists " + keyColumns.size() + " columns but keyProperty lists "
                            + keyProperties.size() + " properties");
        }
        if (keyColumns.isEmpty()) {
            // design §07: without explicit key columns, Oracle returns ROWID and
            // PostgreSQL returns every column — warn while the IDE is still open
            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING,
                    "useGeneratedKeys without keyColumn falls back to RETURN_GENERATED_KEYS,"
                            + " which returns ROWID on Oracle and all columns on PostgreSQL."
                            + " Name the key column(s) explicitly (design §07).", method);
        }

        List<KeyModel.Assignment> assignments = new ArrayList<>();
        for (String rawProperty : keyProperties) {
            String propertyPath = rawProperty.trim();
            List<String> path = List.of(propertyPath.split("\\.", -1));
            TypeElement bean;
            String target;
            if (batch != null) {
                if (path.size() != 1) {
                    throw new LightBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\": batch keys assign element"
                                    + " properties directly (one level)");
                }
                bean = batchElementType;
                target = batch.paramName() + ".get(i)";
            } else if (params.size() == 1 && !hasParamAnnotation && path.size() == 1) {
                Map.Entry<String, VariableElement> sole = params.entrySet().iterator().next();
                bean = typeResolver.typeElementOf(sole.getValue().asType());
                if (bean == null || typeResolver.valueKindOf(sole.getValue().asType()) != null) {
                    throw new LightBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\" needs a bean parameter to land on");
                }
                target = sole.getKey();
            } else if (path.size() == 2) {
                VariableElement param = params.get(path.get(0));
                if (param == null) {
                    // the MyBatis runtime ExecutorException, moved to compile time
                    throw new LightBatisProcessingException(method,
                            "keyProperty \"" + propertyPath + "\": no parameter named \""
                                    + path.get(0) + "\". With multiple parameters, keyProperty"
                                    + " must include the parameter name (e.g. \"u.id\")."
                                    + " Available: " + String.join(", ", params.keySet()));
                }
                bean = typeResolver.typeElementOf(param.asType());
                if (bean == null) {
                    throw new LightBatisProcessingException(param,
                            "keyProperty \"" + propertyPath + "\": parameter " + path.get(0)
                                    + " is not a bean");
                }
                target = path.get(0);
                path = path.subList(1, 2);
            } else {
                throw new LightBatisProcessingException(method,
                        "keyProperty \"" + propertyPath + "\" does not resolve; use \"property\""
                                + " (single bean parameter) or \"param.property\"");
            }
            TypeResolver.PropertyWrite write = typeResolver.setterFor(bean, path.get(0), method);
            ValueKind keyKind = typeResolver.valueKindOf(write.type());
            if (keyKind == null || !keyReadable(keyKind)) {
                throw new LightBatisProcessingException(method,
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
