package io.github.larkbatis.processor.frontend;

import io.github.larkbatis.processor.frontend.expr.ExprTypes;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * {@link ExprTypes} over one mapper method's parameters: the expression
 * compiler's window into javax.lang.model. Root resolution mirrors {@code #{}}
 * resolution (a single unannotated bean parameter exposes its properties
 * directly; otherwise the first segment names a parameter or paramN alias) —
 * one rule set for values and conditions alike. Property paths may go deeper
 * than the one-hop bind limit: conditions navigate, binds stay flat.
 */
final class MethodExprTypes implements ExprTypes {

    private final TypeResolver typeResolver;
    private final ExecutableElement method;
    private final LinkedHashMap<String, VariableElement> params;
    private final boolean hasParamAnnotation;
    /** Values carry only strings; the mirror rides along here (record equality keys). */
    private final Map<Value, TypeMirror> mirrors = new HashMap<>();

    MethodExprTypes(TypeResolver typeResolver, ExecutableElement method,
            LinkedHashMap<String, VariableElement> params, boolean hasParamAnnotation) {
        this.typeResolver = typeResolver;
        this.method = method;
        this.params = params;
        this.hasParamAnnotation = hasParamAnnotation;
    }

    @Override
    public Value root(String name) {
        VariableElement param = params.get(name);
        String paramName = name;
        if (param == null) {
            int generic = genericIndexOf(name);
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
        if (param != null) {
            return value(paramName, param.asType(), List.of());
        }

        // single unannotated bean parameter: properties are the roots, like
        // OGNL over the bare parameter object. The implicit receiver itself is
        // deliberately NOT null-guarded: a null bean parameter is NPE by
        // design, matching how #{} binds resolve, and matching the shape the
        // generated condition takes (boolean c0 = q.getName() != null).
        if (params.size() == 1 && !hasParamAnnotation) {
            Map.Entry<String, VariableElement> sole = params.entrySet().iterator().next();
            TypeMirror soleType = sole.getValue().asType();
            if (typeResolver.valueKindOf(soleType) == null && !typeResolver.isSqlFragment(soleType)) {
                Value bean = new Value(sole.getKey(), soleType.toString(), null, List.of());
                mirrors.put(bean, soleType);
                return property(bean, name);
            }
        }
        throw new LarkBatisProcessingException(method,
                "\"" + name + "\" does not match any parameter. Available: "
                        + String.join(", ", params.keySet()));
    }

    @Override
    public Value property(Value receiver, String name) {
        TypeMirror receiverType = mirror(receiver);
        if (typeResolver.valueKindOf(receiverType) != null) {
            throw new LarkBatisProcessingException(method,
                    "\"" + receiver.javaExpr() + "\" has type " + receiver.typeFqn()
                            + " and no navigable properties");
        }
        TypeElement bean = typeResolver.typeElementOf(receiverType);
        if (bean == null || typeResolver.isCollectionLike(receiverType)) {
            throw new LarkBatisProcessingException(method,
                    "\"" + receiver.javaExpr() + "\" has type " + receiver.typeFqn()
                            + "; collections offer size()/isEmpty(), not properties");
        }
        TypeResolver.PropertyRead read = typeResolver.getterFor(bean, name, method);
        return value(receiver.javaExpr() + "." + read.accessorCall(), read.type(),
                receiver.nullableSteps());
    }

    @Override
    public Call method(Value receiver, String name, List<Literal> args) {
        TypeMirror receiverType = mirror(receiver);
        ExecutableElement target = typeResolver.findInstanceMethod(receiverType, name, args.size());
        if (target == null) {
            throw new LarkBatisProcessingException(method,
                    "no method " + name + "(" + args.size() + " arg"
                            + (args.size() == 1 ? "" : "s") + ") on " + receiver.typeFqn());
        }
        for (int i = 0; i < args.size(); i++) {
            TypeMirror paramType = target.getParameters().get(i).asType();
            if (!literalFits(args.get(i), paramType)) {
                throw new LarkBatisProcessingException(method,
                        name + "(): argument " + args.get(i).javaText()
                                + " does not fit parameter type " + paramType);
            }
        }
        ValueKind returnKind = switch (target.getReturnType().getKind()) {
            case BOOLEAN -> ValueKind.PRIM_BOOLEAN;
            case INT -> ValueKind.PRIM_INT;
            case LONG -> ValueKind.PRIM_LONG;
            case DECLARED -> "java.lang.Boolean".equals(
                    typeResolver.typeElementOf(target.getReturnType()) == null ? null
                            : typeResolver.typeElementOf(target.getReturnType())
                                    .getQualifiedName().toString())
                    ? ValueKind.BOX_BOOLEAN
                    : null;
            default -> null;
        };
        if (returnKind == null) {
            throw new LarkBatisProcessingException(method,
                    name + "() on " + receiver.typeFqn() + " returns "
                            + target.getReturnType() + "; conditions accept boolean methods"
                            + " and int-returning size/length");
        }
        StringBuilder call = new StringBuilder(receiver.javaExpr()).append('.').append(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            call.append(i == 0 ? "" : ", ").append(args.get(i).javaText());
        }
        return new Call(call.append(')').toString(), returnKind);
    }

    @Override
    public boolean enumHasConstant(String enumFqn, String constantName) {
        return typeResolver.enumHasConstant(enumFqn, constantName);
    }

    // --- helpers ---------------------------------------------------------------

    private Value value(String javaExpr, TypeMirror type, List<String> parentSteps) {
        List<String> steps = new ArrayList<>(parentSteps);
        if (type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ARRAY) {
            steps.add(javaExpr);
        }
        Value value = new Value(javaExpr, type.toString(), typeResolver.valueKindOf(type),
                List.copyOf(steps));
        mirrors.put(value, type);
        return value;
    }

    private TypeMirror mirror(Value value) {
        TypeMirror mirror = mirrors.get(value);
        if (mirror == null) {
            throw new IllegalStateException("unresolved expression value: " + value.javaExpr());
        }
        return mirror;
    }

    private boolean literalFits(Literal literal, TypeMirror paramType) {
        return switch (literal.kind()) {
            case STRING -> {
                TypeElement element = typeResolver.typeElementOf(paramType);
                String fqn = element == null ? "" : element.getQualifiedName().toString();
                yield fqn.equals("java.lang.String") || fqn.equals("java.lang.CharSequence")
                        || fqn.equals("java.lang.Object");
            }
            case NUMBER -> switch (paramType.getKind()) {
                case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
                case DECLARED -> typeResolver.valueKindOf(paramType) != null
                        && !typeResolver.valueKindOf(paramType).primitive()
                        && switch (typeResolver.valueKindOf(paramType)) {
                            case BOX_BYTE, BOX_SHORT, BOX_INT, BOX_LONG,
                                 BOX_FLOAT, BOX_DOUBLE -> true;
                            default -> false;
                        };
                default -> false;
            };
            case BOOLEAN -> paramType.getKind() == TypeKind.BOOLEAN
                    || typeResolver.valueKindOf(paramType) == ValueKind.BOX_BOOLEAN;
            case NULL -> false;
        };
    }

    /** paramN aliases, matching ParamNameResolver's generic names. */
    private static int genericIndexOf(String name) {
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
