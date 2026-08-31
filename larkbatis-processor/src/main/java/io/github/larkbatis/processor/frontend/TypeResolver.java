package io.github.larkbatis.processor.frontend;

import io.github.larkbatis.annotations.Column;
import io.github.larkbatis.annotations.Handler;
import io.github.larkbatis.processor.ir.ColumnNaming;
import io.github.larkbatis.processor.ir.PropertyModel;
import io.github.larkbatis.processor.ir.ResultModel;
import io.github.larkbatis.processor.ir.SqlPiece;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Resolves Java types to JDBC move strategies. The
 * whitelist is deliberate: an unsupported type is a clear compile-time error
 * naming the element, never silently-wrong generated code.
 */
public final class TypeResolver {

    public static final String SQL_FRAGMENT_FQN = "io.github.larkbatis.runtime.SqlFragment";
    public static final String HANDLER_FQN =
            "io.github.larkbatis.runtime.LarkBatisTypeHandler";

    private final Elements elements;
    private final Types types;
    private final ColumnNaming columnNaming;
    private final TypeHandlerDefaults typeHandlerDefaults;

    public TypeResolver(Elements elements, Types types, ColumnNaming columnNaming,
            TypeHandlerDefaults typeHandlerDefaults) {
        this.elements = elements;
        this.types = types;
        this.columnNaming = columnNaming;
        this.typeHandlerDefaults = typeHandlerDefaults;
    }

    /**
     * The default handler for a value of this type, or null. Consulted only
     * after {@code @Handler} and a {@code typeHandler} attribute have both
     * declined, so naming a handler at the site always wins.
     */
    public String defaultHandlerFor(TypeMirror valueType) {
        if (typeHandlerDefaults.isEmpty()) {
            return null;
        }
        TypeMirror erased = types.erasure(boxed(valueType));
        return erased.getKind() == TypeKind.DECLARED
                ? typeHandlerDefaults.handlerFor(
                        ((TypeElement) ((DeclaredType) erased).asElement())
                                .getQualifiedName().toString())
                : null;
    }

    /** Whether a value of this type would pick up a default handler. */
    public boolean hasDefaultHandlerFor(TypeMirror valueType) {
        if (typeHandlerDefaults.isEmpty()) {
            return false;
        }
        TypeMirror erased = types.erasure(boxed(valueType));
        return erased.getKind() == TypeKind.DECLARED
                && typeHandlerDefaults.covers(((TypeElement) ((DeclaredType) erased).asElement())
                        .getQualifiedName().toString());
    }

    /**
     * Everything wrong with {@code -Alarkbatis.typeHandlers}, one message
     * each. Checked once for the whole compilation rather than where an entry
     * is used, because an entry no property happens to have is exactly the one
     * a typo produces, and it would otherwise never be looked at.
     */
    public List<String> typeHandlerDefaultProblems() {
        List<String> problems = new ArrayList<>(typeHandlerDefaults.syntaxProblems());
        for (Map.Entry<String, String> entry : typeHandlerDefaults.entries().entrySet()) {
            TypeElement javaType = elements.getTypeElement(entry.getKey());
            if (javaType == null) {
                problems.add("larkbatis.typeHandlers names java type " + entry.getKey()
                        + ", which is not on the compilation classpath");
                continue;
            }
            TypeElement handler = elements.getTypeElement(entry.getValue());
            if (handler == null) {
                problems.add("larkbatis.typeHandlers names handler " + entry.getValue()
                        + ", which is not on the compilation classpath");
                continue;
            }
            try {
                validateHandlerAt(null, handler.asType(), javaType.asType(),
                        "larkbatis.typeHandlers entry " + entry.getKey() + ":" + entry.getValue());
            } catch (LarkBatisProcessingException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /** Registered types that moved nothing — a stale or mistyped entry. */
    public List<String> unusedTypeHandlerDefaults() {
        return typeHandlerDefaults.unusedJavaTypes();
    }

    /** The move strategy for a type, or null when the type is not supported. */
    public ValueKind valueKindOf(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN: return ValueKind.PRIM_BOOLEAN;
            case BYTE: return ValueKind.PRIM_BYTE;
            case SHORT: return ValueKind.PRIM_SHORT;
            case INT: return ValueKind.PRIM_INT;
            case LONG: return ValueKind.PRIM_LONG;
            case FLOAT: return ValueKind.PRIM_FLOAT;
            case DOUBLE: return ValueKind.PRIM_DOUBLE;
            case CHAR: return ValueKind.PRIM_CHAR;
            case ARRAY:
                return ((ArrayType) type).getComponentType().getKind() == TypeKind.BYTE
                        ? ValueKind.BYTES
                        : null;
            case DECLARED:
                break;
            default:
                return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        if (element.getKind() == ElementKind.ENUM) {
            return ValueKind.ENUM;
        }
        return switch (element.getQualifiedName().toString()) {
            case "java.lang.Boolean" -> ValueKind.BOX_BOOLEAN;
            case "java.lang.Byte" -> ValueKind.BOX_BYTE;
            case "java.lang.Short" -> ValueKind.BOX_SHORT;
            case "java.lang.Integer" -> ValueKind.BOX_INT;
            case "java.lang.Long" -> ValueKind.BOX_LONG;
            case "java.lang.Float" -> ValueKind.BOX_FLOAT;
            case "java.lang.Double" -> ValueKind.BOX_DOUBLE;
            case "java.lang.Character" -> ValueKind.BOX_CHARACTER;
            case "java.lang.String" -> ValueKind.STRING;
            case "java.math.BigDecimal" -> ValueKind.BIG_DECIMAL;
            case "java.math.BigInteger" -> ValueKind.BIG_INTEGER;
            case "java.time.LocalDate" -> ValueKind.LOCAL_DATE;
            case "java.time.LocalTime" -> ValueKind.LOCAL_TIME;
            case "java.time.LocalDateTime" -> ValueKind.LOCAL_DATE_TIME;
            case "java.time.Instant" -> ValueKind.INSTANT;
            case "java.time.OffsetDateTime" -> ValueKind.OFFSET_DATE_TIME;
            case "java.time.OffsetTime" -> ValueKind.OFFSET_TIME;
            case "java.time.ZonedDateTime" -> ValueKind.ZONED_DATE_TIME;
            // java.sql.Date extends java.util.Date, so the order of these four
            // would matter to an instanceof test; matching on the qualified
            // name means it cannot.
            case "java.sql.Date" -> ValueKind.SQL_DATE;
            case "java.sql.Time" -> ValueKind.SQL_TIME;
            case "java.sql.Timestamp" -> ValueKind.SQL_TIMESTAMP;
            case "java.util.Date" -> ValueKind.UTIL_DATE;
            default -> null;
        };
    }

    /** Enum FQN when the type is an enum, else null. */
    public String enumTypeOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        return element.getKind() == ElementKind.ENUM
                ? element.getQualifiedName().toString()
                : null;
    }

    public boolean isSqlFragment(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && ((TypeElement) ((DeclaredType) type).asElement())
                        .getQualifiedName().contentEquals(SQL_FRAGMENT_FQN);
    }

    /** The element type when {@code type} is exactly {@code java.util.List<T>}, else null. */
    public TypeMirror listElementOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        if (!element.getQualifiedName().contentEquals("java.util.List")
                || declared.getTypeArguments().size() != 1) {
            return null;
        }
        return declared.getTypeArguments().get(0);
    }

    /** The element type when {@code type} is exactly {@code java.util.stream.Stream<T>}, else null. */
    public TypeMirror streamElementOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        if (!element.getQualifiedName().contentEquals("java.util.stream.Stream")
                || declared.getTypeArguments().size() != 1) {
            return null;
        }
        return declared.getTypeArguments().get(0);
    }

    /** The TypeElement behind a declared type, or null. */
    public TypeElement typeElementOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    /**
     * The one accessor problem that is not the user's fault. Lombok writes its
     * getters and setters into the AST when *its* processor runs, so a
     * processor that runs before it sees a class with none — and the honest
     * message ("no usable setters") points at the wrong thing entirely.
     *
     * <p>Lombok's annotations are SOURCE-retention, which means they are still
     * in the model here even though they will not be in the class file; seeing
     * one on a class with no accessors is exactly this situation.
     *
     * <p>The fix is one line in the consumer's build: declare
     * {@code larkbatis-processor} after {@code org.projectlombok:lombok} in
     * the {@code annotationProcessor} configuration. javac runs discovered
     * processors in classpath order, so the declaration order is the run
     * order. This is the same coordination problem
     * {@code lombok-mapstruct-binding} exists for.
     */
    private static String lombokHint(TypeElement bean) {
        for (var annotation : bean.getAnnotationMirrors()) {
            String name = annotation.getAnnotationType().toString();
            if (name.startsWith("lombok.")) {
                return ". " + bean.getSimpleName() + " is annotated " + name
                        + ", and Lombok had not generated its accessors yet when LarkBatis"
                        + " looked: declare larkbatis-processor AFTER"
                        + " org.projectlombok:lombok in the annotationProcessor configuration"
                        + " (javac runs processors in classpath order)";
            }
        }
        return "";
    }

    /**
     * Whether a property could be the target of a one-level nested mapping:
     * {@code List<Bean>} for {@code <collection>}, a bean for
     * {@code <association>}. Deliberately narrow — a {@code Map}, a
     * {@code Set} or an {@code Optional} is none of those and stays an error.
     */
    private boolean isNestedCandidate(TypeMirror type) {
        TypeMirror element = listElementOf(type);
        TypeMirror candidate = element != null ? element : type;
        if (candidate.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement bean = (TypeElement) ((DeclaredType) candidate).asElement();
        if (bean.getKind() != ElementKind.CLASS) {
            return false;
        }
        String fqn = bean.getQualifiedName().toString();
        return !fqn.startsWith("java.") && !fqn.startsWith("javax.") && !fqn.startsWith("jakarta.");
    }

    /**
     * The setter for one property, searched subclass-first like
     * {@link #resultModelOf}; null when the class has none.
     */
    public ExecutableElement setterOf(TypeElement bean, String property) {
        String setterName = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        TypeElement current = bean;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement method : ElementFilter.methodsIn(current.getEnclosedElements())) {
                if (method.getSimpleName().contentEquals(setterName)
                        && method.getParameters().size() == 1
                        && !method.getModifiers().contains(Modifier.STATIC)
                        && !method.getModifiers().contains(Modifier.PRIVATE)) {
                    return method;
                }
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        return null;
    }

    // --- result classes -------------------------------------------------------

    /**
     * Builds the {@link ResultModel} of a bean result class: writable
     * properties in declaration order (the canonical column order of the
     * positional reader), subclass before superclass.
     */
    public ResultModel resultModelOf(TypeElement resultClass) {
        return resultModelOf(resultClass, Map.of());
    }

    /**
     * @param xmlHandlers property name to handler FQN, from a
     *                    {@code <resultMap>}'s {@code typeHandler} attributes.
     *                    Passed in rather than merged afterwards because the
     *                    unsupported-type check below is what a handler exists
     *                    to get past — merging later would mean the property
     *                    was already rejected.
     */
    public ResultModel resultModelOf(TypeElement resultClass, Map<String, String> xmlHandlers) {
        if (resultClass.getKind() != ElementKind.CLASS) {
            throw new LarkBatisProcessingException(resultClass,
                    "Result type " + resultClass.getQualifiedName()
                            + " must be a class with setters; records and interfaces are not supported yet");
        }
        if (resultClass.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new LarkBatisProcessingException(resultClass,
                    "Result class " + resultClass.getQualifiedName() + " must not be abstract");
        }
        boolean hasNoArgConstructor = ElementFilter.constructorsIn(resultClass.getEnclosedElements())
                .stream()
                .anyMatch(ctor -> ctor.getParameters().isEmpty()
                        && !ctor.getModifiers().contains(Modifier.PRIVATE));
        if (!hasNoArgConstructor) {
            throw new LarkBatisProcessingException(resultClass,
                    "Result class " + resultClass.getQualifiedName()
                            + " needs an accessible no-arg constructor");
        }

        List<PropertyModel> properties = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        TypeElement current = resultClass;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement method : ElementFilter.methodsIn(current.getEnclosedElements())) {
                String name = method.getSimpleName().toString();
                if (!name.startsWith("set") || name.length() <= 3
                        || method.getParameters().size() != 1
                        || method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                String property = decapitalize(name.substring(3));
                if (!seen.add(property)) {
                    continue; // overridden in a subclass — already collected
                }
                TypeMirror propertyType = method.getParameters().get(0).asType();
                String handler = handlerOf(current, method, property, propertyType);
                handler = mergeXmlHandler(method, handler, xmlHandlers.get(property),
                        propertyType, property, resultClass);
                if (handler == null) {
                    handler = defaultHandlerFor(propertyType);
                }
                ValueKind kind = valueKindOf(propertyType);
                if (kind == null && handler == null) {
                    if (isNestedCandidate(propertyType)) {
                        // A List<Bean> or a bean of one's own is not a column and
                        // never was; it is what <collection>/<association> fills
                        // in. Left out of the reader rather than
                        // rejected — but everything else still is, because a
                        // silently unmapped Map or Optional is exactly the
                        // data loss this pass exists to prevent.
                        continue;
                    }
                    throw new LarkBatisProcessingException(method,
                            "Property " + property + " of " + resultClass.getQualifiedName()
                                    + " has unsupported type " + propertyType
                                    + ". Supported: primitives and their boxes, String, BigDecimal,"
                                    + " BigInteger, byte[], java.time (LocalDate/LocalTime/"
                                    + "LocalDateTime/Instant/OffsetDateTime/OffsetTime/"
                                    + "ZonedDateTime), java.util.Date, java.sql.Date/Time/"
                                    + "Timestamp, enums."
                                    + " A one-level <association>/<collection> target may also be"
                                    + " a bean or a List of one. Any other type needs a handler:"
                                    + " @Handler on the property, typeHandler on the <result>"
                                    + " that maps it, or an entry in"
                                    + " -Alarkbatis.typeHandlers.");
                }
                properties.add(new PropertyModel(property, name, kind, enumTypeOf(propertyType),
                        columnOf(current, method, property), handler));
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        if (properties.isEmpty()) {
            throw new LarkBatisProcessingException(resultClass,
                    "Result class " + resultClass.getQualifiedName() + " has no usable setters"
                            + lombokHint(resultClass));
        }
        rejectColumnClash(resultClass, properties, columnNaming);

        String packageName = elements.getPackageOf(resultClass).getQualifiedName().toString();
        return new ResultModel(
                resultClass.getQualifiedName().toString(),
                packageName,
                resultClass.getSimpleName().toString(),
                List.copyOf(properties));
    }

    /**
     * The column a property reads from: {@code @Column} on the setter, on the
     * backing field or on the getter, else null and the naming convention
     * applies. All three sites are read because the annotation targets FIELD
     * and METHOD both, and a user who put it on the one we do not read would
     * get silence instead of a mapping.
     */
    private static String columnOf(TypeElement declaring, ExecutableElement setter,
            String property) {
        String suffix = setter.getSimpleName().toString().substring(3);
        List<Element> sites = new ArrayList<>();
        sites.add(setter);
        for (Element field : ElementFilter.fieldsIn(declaring.getEnclosedElements())) {
            if (field.getSimpleName().contentEquals(property)) {
                sites.add(field);
            }
        }
        for (ExecutableElement getter : ElementFilter.methodsIn(declaring.getEnclosedElements())) {
            String name = getter.getSimpleName().toString();
            if (getter.getParameters().isEmpty()
                    && (name.equals("get" + suffix) || name.equals("is" + suffix))) {
                sites.add(getter);
            }
        }

        String column = null;
        for (Element site : sites) {
            Column annotation = site.getAnnotation(Column.class);
            if (annotation == null) {
                continue;
            }
            String value = annotation.value().trim();
            if (value.isEmpty()) {
                throw new LarkBatisProcessingException(site, "@Column on property " + property
                        + " of " + declaring.getQualifiedName() + " has an empty column name");
            }
            if (column != null && !column.equals(value)) {
                throw new LarkBatisProcessingException(site, "Property " + property + " of "
                        + declaring.getQualifiedName() + " carries two different @Column names, \""
                        + column + "\" and \"" + value + "\" — the field, the getter and the"
                        + " setter must agree");
            }
            column = value;
        }
        return column;
    }

    /**
     * The handler a property moves through: {@code @Handler} on the setter, on
     * the backing field or on the getter. Read on all three sites for the same
     * reason {@code @Column} is — the annotation targets FIELD and METHOD
     * both, and the site the user picks has to be the site that is read.
     *
     * <p>A handler here is what lets a property have a type the whitelist does
     * not know: the check that rejects an unsupported type runs only when this
     * returns null.
     */
    private String handlerOf(TypeElement declaring, ExecutableElement setter, String property,
            TypeMirror propertyType) {
        String handler = null;
        for (Element site : annotationSites(declaring, setter, property)) {
            TypeMirror declared = handlerTypeOf(site);
            if (declared == null) {
                continue;
            }
            String fqn = validateHandler(site, declared, propertyType,
                    "property " + property + " of " + declaring.getQualifiedName());
            if (handler != null && !handler.equals(fqn)) {
                throw new LarkBatisProcessingException(site, "Property " + property + " of "
                        + declaring.getQualifiedName() + " carries two different @Handler"
                        + " classes, " + handler + " and " + fqn + " — the field, the getter"
                        + " and the setter must agree");
            }
            handler = fqn;
        }
        return handler;
    }

    /**
     * The three elements one property can be annotated on: the setter, the
     * backing field and the getter. {@code @Column} and {@code @Handler} both
     * read all three — the annotations target FIELD and METHOD both, and the
     * site the user picks has to be the site that is read.
     */
    private static List<Element> annotationSites(TypeElement declaring, ExecutableElement setter,
            String property) {
        String suffix = setter.getSimpleName().toString().substring(3);
        List<Element> sites = new ArrayList<>();
        sites.add(setter);
        for (Element field : ElementFilter.fieldsIn(declaring.getEnclosedElements())) {
            if (field.getSimpleName().contentEquals(property)) {
                sites.add(field);
            }
        }
        for (ExecutableElement getter : ElementFilter.methodsIn(declaring.getEnclosedElements())) {
            String name = getter.getSimpleName().toString();
            if (getter.getParameters().isEmpty()
                    && (name.equals("get" + suffix) || name.equals("is" + suffix))) {
                sites.add(getter);
            }
        }
        return sites;
    }

    /**
     * The element carrying {@code @Handler} for a property of {@code bean}, or
     * null. Only used to <em>refuse</em> a handler where the generated shape
     * cannot honour one — silently dropping it is the failure mode this whole
     * annotation exists to remove.
     */
    public Element handlerSiteOn(TypeElement bean, String property) {
        TypeElement current = bean;
        String setterName = "set" + Character.toUpperCase(property.charAt(0))
                + property.substring(1);
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement method : ElementFilter.methodsIn(current.getEnclosedElements())) {
                if (method.getSimpleName().contentEquals(setterName)
                        && method.getParameters().size() == 1) {
                    for (Element site : annotationSites(current, method, property)) {
                        if (handlerTypeOf(site) != null) {
                            return site;
                        }
                    }
                    return null;
                }
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        return null;
    }

    /**
     * The handler for one property when both a {@code @Handler} and a
     * {@code <resultMap>} could name one. They must agree: there is one reader
     * per result class, so there is one answer per property, and a silent
     * winner between two declarations is the kind of rule nobody remembers.
     */
    private String mergeXmlHandler(ExecutableElement setter, String declared, String fromXml,
            TypeMirror propertyType, String property, TypeElement resultClass) {
        if (fromXml == null) {
            return declared;
        }
        TypeElement handlerClass = elements.getTypeElement(fromXml);
        if (handlerClass == null) {
            // the statement-scoped message names the file and the statement, so
            // let that one be the error the user sees
            return declared;
        }
        String fqn = validateHandler(setter, handlerClass.asType(), propertyType,
                "property " + property + " of " + resultClass.getQualifiedName());
        if (declared != null && !declared.equals(fqn)) {
            throw new LarkBatisProcessingException(setter, "Property " + property + " of "
                    + resultClass.getQualifiedName() + " has @Handler(" + declared + ") but a"
                    + " <resultMap> names typeHandler=\"" + fqn + "\" — one reader is generated"
                    + " per result class, so the two have to agree");
        }
        return fqn;
    }

    /** The class named by {@code @Handler} on this element, or null. */
    public static TypeMirror handlerTypeOf(Element site) {
        Handler annotation = site.getAnnotation(Handler.class);
        if (annotation == null) {
            return null;
        }
        try {
            // the class is not loaded during compilation, so reading value()
            // always throws and the mirror comes out of the exception
            annotation.value();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    /**
     * Everything about a handler that has to hold before generated code may
     * name it. All of it is decidable here, which is the point: the annotation
     * cannot bound its own {@code Class<?>} — {@code larkbatis-annotations}
     * has no dependencies, so there is nothing on its module path to bound it
     * with — so the check that javac would have done is done here instead, with
     * a message that says which rule was broken.
     *
     * @param what how to name the annotated thing in an error message
     * @return the handler's fully-qualified name
     */
    public String validateHandler(Element site, TypeMirror handlerType, TypeMirror valueType,
            String what) {
        return validateHandlerAt(site, handlerType, valueType, "@Handler on " + what);
    }

    /**
     * The same checks, named after whatever asked for the handler — the
     * annotation at a site, or an entry of {@code -Alarkbatis.typeHandlers},
     * which has no element to point at.
     */
    private String validateHandlerAt(Element site, TypeMirror handlerType, TypeMirror valueType,
            String where) {
        if (handlerType.getKind() != TypeKind.DECLARED) {
            throw new LarkBatisProcessingException(site,
                    where + " names " + handlerType + ", which is not a class");
        }
        TypeElement element = (TypeElement) ((DeclaredType) handlerType).asElement();
        String fqn = element.getQualifiedName().toString();

        DeclaredType handlerInterface = handlerInterfaceOf(handlerType);
        if (handlerInterface == null) {
            throw new LarkBatisProcessingException(site, where + " names " + fqn
                    + ", which does not implement " + HANDLER_FQN);
        }
        if (handlerInterface.getTypeArguments().isEmpty()) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " implements " + HANDLER_FQN + " raw. Give it a type argument, so the"
                    + " build can check it against the value's type");
        }
        TypeMirror handled = types.erasure(handlerInterface.getTypeArguments().get(0));
        TypeMirror wanted = types.erasure(boxed(valueType));
        if (!types.isSameType(handled, wanted)) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " handles " + handled + ", but the value is " + wanted
                    + ". The handler's type argument must be the value's own type — a supertype"
                    + " would not survive the assignment the generated reader makes");
        }

        if (element.getKind() != ElementKind.CLASS || element.getModifiers().contains(
                Modifier.ABSTRACT)) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " must be a concrete class — generated code instantiates it directly");
        }
        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " must be public. Generated code is not always in its package");
        }
        if (element.getNestingKind().isNested()
                && !element.getModifiers().contains(Modifier.STATIC)) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " is an inner class, which cannot be constructed without an enclosing"
                    + " instance. Make it static");
        }
        boolean constructible = ElementFilter.constructorsIn(element.getEnclosedElements())
                .stream()
                .anyMatch(ctor -> ctor.getParameters().isEmpty()
                        && ctor.getModifiers().contains(Modifier.PUBLIC));
        if (!constructible) {
            throw new LarkBatisProcessingException(site, where + ": " + fqn
                    + " needs a public no-argument constructor. One instance is held in a"
                    + " static final field and shared by every caller, so a handler that needs"
                    + " construction arguments is also a handler that is not stateless");
        }
        return fqn;
    }

    /** The {@code LarkBatisTypeHandler<...>} among a type's supertypes, or null. */
    private DeclaredType handlerInterfaceOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        if (element.getQualifiedName().contentEquals(HANDLER_FQN)) {
            return (DeclaredType) type;
        }
        for (TypeMirror supertype : types.directSupertypes(type)) {
            DeclaredType found = handlerInterfaceOf(supertype);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A handler's type argument is always a reference type; the value's may not be. */
    private TypeMirror boxed(TypeMirror type) {
        return type.getKind().isPrimitive()
                ? types.boxedClass((PrimitiveType) type).asType()
                : type;
    }

    /**
     * Two properties resolving to the same column cannot both be read: the
     * name-based reader switches on that key, so javac would reject the
     * duplicate case label in generated code. Said here, on the class, instead
     * of as an error inside code the user did not write.
     */
    private static void rejectColumnClash(TypeElement resultClass,
            List<PropertyModel> properties, ColumnNaming naming) {
        Map<String, PropertyModel> byKey = new LinkedHashMap<>();
        for (PropertyModel property : properties) {
            PropertyModel clash = byKey.putIfAbsent(property.matchKey(naming), property);
            if (clash != null) {
                // "usr_email" and "usrEmail" are one column here; say why when
                // the two spellings differ, and skip the noise when they do not
                String same = clash.columnName().equals(property.columnName())
                        ? ""
                        : " (\"" + clash.columnName() + "\" and \"" + property.columnName()
                                + "\" are one column: labels match case-insensitively"
                                + (naming.ignoresUnderscores() ? " with underscores ignored" : "")
                                + ")";
                throw new LarkBatisProcessingException(resultClass,
                        "Properties " + clash.name() + " and " + property.name() + " of "
                                + resultClass.getQualifiedName() + " both read column \""
                                + property.columnName() + "\"" + same
                                + " — rename one, or point one at another column with @Column");
            }
        }
    }

    // --- bean property reads (parameters) --------------------------------------

    /** A resolved read of one property: the accessor call and the value type. */
    public record PropertyRead(String accessorCall, TypeMirror type) {
    }

    /**
     * Finds the getter for {@code property} on {@code bean}: JavaBeans
     * {@code getX()}/{@code isX()}, or a record-style {@code x()} accessor.
     */
    public PropertyRead getterFor(TypeElement bean, String property, Element errorSite) {
        String capitalized = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        List<String> candidates = List.of("get" + capitalized, "is" + capitalized, property);
        TypeElement current = bean;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement method : ElementFilter.methodsIn(current.getEnclosedElements())) {
                if (!method.getParameters().isEmpty()
                        || method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.PRIVATE)
                        || method.getReturnType().getKind() == TypeKind.VOID) {
                    continue;
                }
                String name = method.getSimpleName().toString();
                if (candidates.contains(name)) {
                    return new PropertyRead(name + "()", method.getReturnType());
                }
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        throw new LarkBatisProcessingException(errorSite,
                "No readable property \"" + property + "\" on " + bean.getQualifiedName()
                        + " (looked for " + String.join(", ", candidates) + ")"
                        + lombokHint(bean));
    }

    /** Finds the setter for {@code property} on {@code bean}, with its value type. */
    public record PropertyWrite(String setterName, TypeMirror type) {
    }

    public PropertyWrite setterFor(TypeElement bean, String property, Element errorSite) {
        String setterName = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        TypeElement current = bean;
        while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
            for (ExecutableElement method : ElementFilter.methodsIn(current.getEnclosedElements())) {
                if (method.getSimpleName().contentEquals(setterName)
                        && method.getParameters().size() == 1
                        && !method.getModifiers().contains(Modifier.STATIC)
                        && !method.getModifiers().contains(Modifier.PRIVATE)) {
                    return new PropertyWrite(setterName, method.getParameters().get(0).asType());
                }
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        throw new LarkBatisProcessingException(errorSite,
                "No writable property \"" + property + "\" on " + bean.getQualifiedName()
                        + " (looked for " + setterName + ")");
    }

    // --- expression support (the <if test> grammar) ------------------------------

    /** Whether {@code type} is assignable to Collection or Map (for size()/isEmpty()). */
    public boolean isCollectionLike(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeMirror erased = types.erasure(type);
        for (String base : List.of("java.util.Collection", "java.util.Map")) {
            TypeElement element = elements.getTypeElement(base);
            if (element != null && types.isAssignable(erased, types.erasure(element.asType()))) {
                return true;
            }
        }
        return false;
    }

    // --- <foreach> ---------------------------------------------------------------

    /**
     * How a {@code <foreach>} collection is iterated, resolved statically.
     *
     * @param kind        loop shape
     * @param elementType what {@code item} is bound to: the element, or a
     *                    map's value type
     * @param indexType   what {@code index} is bound to: {@code int} for a
     *                    collection or array, the key type for a map
     * @param loopType    the type of the enhanced-for variable, which is the
     *                    element for a collection/array and
     *                    {@code Map.Entry<K,V>} for a map
     */
    public record Iteration(SqlPiece.Foreach.Iteration kind, TypeMirror elementType,
                            TypeMirror indexType, String loopType) {
    }

    /**
     * Resolves a {@code <foreach>} collection type, narrowed to statically-typed
     * collections: {@code Collection<T>}, {@code T[]} and
     * {@code Map<K,V>} (the {@code Map.Entry} iteration of MyBatis issue
     * #709). Anything raw, wildcarded or merely {@code Iterable} is rejected
     * by the caller, which owns the message.
     */
    public Iteration iterationOf(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((javax.lang.model.type.ArrayType) type).getComponentType();
            return new Iteration(SqlPiece.Foreach.Iteration.ARRAY, component,
                    intType(), component.toString());
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        DeclaredType declared = (DeclaredType) type;
        // A Collection has size(); a bare Iterable does not, and counting by
        // iterating the collection twice is not worth the surprise.
        TypeMirror element = supertypeArguments(declared, "java.util.Collection");
        if (element != null) {
            return new Iteration(SqlPiece.Foreach.Iteration.COLLECTION, element,
                    intType(), element.toString());
        }
        return mapIteration(declared);
    }

    private Iteration mapIteration(DeclaredType declared) {
        TypeElement mapElement = elements.getTypeElement("java.util.Map");
        if (mapElement == null
                || !types.isAssignable(types.erasure(declared), types.erasure(mapElement.asType()))) {
            return null;
        }
        List<? extends TypeMirror> args = typeArgumentsOf(declared, mapElement);
        if (args == null || args.size() != 2) {
            return null;
        }
        String entryType = "java.util.Map.Entry<" + args.get(0) + ", " + args.get(1) + ">";
        return new Iteration(SqlPiece.Foreach.Iteration.MAP_ENTRY, args.get(1), args.get(0),
                entryType);
    }

    /** Whether {@code type} is assignable to {@code baseFqn} after erasure. */
    public boolean isAssignableTo(TypeMirror type, String baseFqn) {
        TypeElement base = elements.getTypeElement(baseFqn);
        return base != null && type.getKind() == TypeKind.DECLARED
                && types.isAssignable(types.erasure(type), types.erasure(base.asType()));
    }

    /** The single type argument {@code declared} supplies to {@code baseFqn}, or null. */
    private TypeMirror supertypeArguments(DeclaredType declared, String baseFqn) {
        TypeElement base = elements.getTypeElement(baseFqn);
        if (base == null
                || !types.isAssignable(types.erasure(declared), types.erasure(base.asType()))) {
            return null;
        }
        List<? extends TypeMirror> args = typeArgumentsOf(declared, base);
        return args != null && args.size() == 1 ? args.get(0) : null;
    }

    /**
     * The type arguments {@code declared} supplies to {@code base}, found by
     * walking up the supertype graph — {@code List<Long>} answers
     * {@code [Long]} for {@code Collection}, and so does a
     * {@code class Ids implements Collection<Long>}.
     */
    private List<? extends TypeMirror> typeArgumentsOf(DeclaredType declared, TypeElement base) {
        if (types.asElement(declared) == base) {
            return declared.getTypeArguments();
        }
        for (TypeMirror supertype : types.directSupertypes(declared)) {
            if (supertype instanceof DeclaredType superDeclared) {
                List<? extends TypeMirror> found = typeArgumentsOf(superDeclared, base);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private TypeMirror intType() {
        return types.getPrimitiveType(TypeKind.INT);
    }

    /**
     * Finds a visible instance method by name and arity, inherited members
     * included — the "boolean method on a statically-known type" of the
     * expression grammar.
     */
    public ExecutableElement findInstanceMethod(TypeMirror receiver, String name, int argCount) {
        TypeElement element = typeElementOf(receiver);
        if (element == null) {
            return null;
        }
        for (Element member : elements.getAllMembers(element)) {
            if (member instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals(name)
                    && method.getParameters().size() == argCount
                    && !method.getModifiers().contains(Modifier.STATIC)
                    && !method.getModifiers().contains(Modifier.PRIVATE)) {
                return method;
            }
        }
        return null;
    }

    public boolean enumHasConstant(String enumFqn, String constantName) {
        TypeElement element = elements.getTypeElement(enumFqn);
        if (element == null || element.getKind() != ElementKind.ENUM) {
            return false;
        }
        return element.getEnclosedElements().stream()
                .anyMatch(e -> e.getKind() == ElementKind.ENUM_CONSTANT
                        && e.getSimpleName().contentEquals(constantName));
    }

    public static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name; // JavaBeans rule: "URL" stays "URL"
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
