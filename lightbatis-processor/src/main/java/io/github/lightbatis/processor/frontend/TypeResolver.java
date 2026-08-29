package io.github.lightbatis.processor.frontend;

import io.github.lightbatis.processor.ir.PropertyModel;
import io.github.lightbatis.processor.ir.ResultModel;
import io.github.lightbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Resolves Java types to JDBC move strategies (build plan §05, task 7). The
 * whitelist is deliberate: an unsupported type is a clear compile-time error
 * naming the element, never silently-wrong generated code (build plan §08,
 * risk 3).
 */
public final class TypeResolver {

    public static final String SQL_FRAGMENT_FQN = "io.github.lightbatis.runtime.SqlFragment";

    private final Elements elements;
    private final Types types;

    public TypeResolver(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
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
            case "java.lang.String" -> ValueKind.STRING;
            case "java.math.BigDecimal" -> ValueKind.BIG_DECIMAL;
            case "java.time.LocalDate" -> ValueKind.LOCAL_DATE;
            case "java.time.LocalTime" -> ValueKind.LOCAL_TIME;
            case "java.time.LocalDateTime" -> ValueKind.LOCAL_DATE_TIME;
            case "java.time.Instant" -> ValueKind.INSTANT;
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

    /** The TypeElement behind a declared type, or null. */
    public TypeElement typeElementOf(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    // --- result classes -------------------------------------------------------

    /**
     * Builds the {@link ResultModel} of a bean result class: writable
     * properties in declaration order (the canonical column order of the
     * positional reader), subclass before superclass.
     */
    public ResultModel resultModelOf(TypeElement resultClass) {
        if (resultClass.getKind() != ElementKind.CLASS) {
            throw new LightBatisProcessingException(resultClass,
                    "Result type " + resultClass.getQualifiedName()
                            + " must be a class with setters; records and interfaces are not supported yet");
        }
        if (resultClass.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new LightBatisProcessingException(resultClass,
                    "Result class " + resultClass.getQualifiedName() + " must not be abstract");
        }
        boolean hasNoArgConstructor = ElementFilter.constructorsIn(resultClass.getEnclosedElements())
                .stream()
                .anyMatch(ctor -> ctor.getParameters().isEmpty()
                        && !ctor.getModifiers().contains(Modifier.PRIVATE));
        if (!hasNoArgConstructor) {
            throw new LightBatisProcessingException(resultClass,
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
                ValueKind kind = valueKindOf(propertyType);
                if (kind == null) {
                    throw new LightBatisProcessingException(method,
                            "Property " + property + " of " + resultClass.getQualifiedName()
                                    + " has unsupported type " + propertyType
                                    + ". Supported: primitives and their boxes, String, BigDecimal,"
                                    + " byte[], LocalDate/LocalTime/LocalDateTime/Instant, enums.");
                }
                properties.add(new PropertyModel(property, name, kind, enumTypeOf(propertyType)));
            }
            TypeMirror superType = current.getSuperclass();
            current = superType.getKind() == TypeKind.DECLARED
                    ? (TypeElement) ((DeclaredType) superType).asElement()
                    : null;
        }
        if (properties.isEmpty()) {
            throw new LightBatisProcessingException(resultClass,
                    "Result class " + resultClass.getQualifiedName() + " has no usable setters");
        }

        String packageName = elements.getPackageOf(resultClass).getQualifiedName().toString();
        return new ResultModel(
                resultClass.getQualifiedName().toString(),
                packageName,
                resultClass.getSimpleName().toString(),
                List.copyOf(properties));
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
        throw new LightBatisProcessingException(errorSite,
                "No readable property \"" + property + "\" on " + bean.getQualifiedName()
                        + " (looked for " + String.join(", ", candidates) + ")");
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
        throw new LightBatisProcessingException(errorSite,
                "No writable property \"" + property + "\" on " + bean.getQualifiedName()
                        + " (looked for " + setterName + ")");
    }

    public static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name; // JavaBeans rule: "URL" stays "URL"
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
