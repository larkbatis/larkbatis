package io.github.larkbatis.processor.frontend.expr;

import io.github.larkbatis.processor.ir.ValueKind;
import java.util.List;

/**
 * The typing seam of the expression compiler: resolves property
 * paths and method calls against the statically-known parameter types. The
 * frontends implement this on top of javax.lang.model; tests implement it
 * with a fixed table, keeping the compiler itself pure string logic.
 */
public interface ExprTypes {

    /**
     * Resolves the first identifier of a path: a parameter name (or paramN
     * alias), or — with a single unannotated bean parameter — one of the
     * bean's properties, matching {@code #{}} resolution.
     */
    Value root(String name);

    /** Resolves one property hop on a bean-typed value. */
    Value property(Value receiver, String name);

    /**
     * Resolves a method call ending a path: {@code size()}, {@code length()},
     * {@code isEmpty()}, or a boolean-returning method with literal arguments
     * on a statically-known type (the grammar). Throws when the method
     * does not exist, is not accessible, or returns anything else.
     */
    Call method(Value receiver, String name, List<Literal> args);

    /** Whether {@code enumFqn} declares a constant named {@code constantName}. */
    boolean enumHasConstant(String enumFqn, String constantName);

    /**
     * One resolved value in an expression.
     *
     * @param javaExpr      accessor expression, e.g. {@code "q.getName()"}
     * @param typeFqn       declared type, for error messages
     * @param kind          JDBC move strategy when scalar; null for beans,
     *                      collections, and anything else outside the whitelist
     * @param nullableSteps accessor expression of every reference-typed step
     *                      along the path, in order, including {@code javaExpr}
     *                      itself when its type is a reference type — the raw
     *                      material of the emitted null guards
     */
    record Value(String javaExpr, String typeFqn, ValueKind kind, List<String> nullableSteps) {

        /** The guard steps excluding the value itself (for null-tolerant uses). */
        public List<String> stepsBeforeSelf() {
            if (nullableSteps.isEmpty()) {
                return nullableSteps;
            }
            return nullableSteps.get(nullableSteps.size() - 1).equals(javaExpr)
                    ? nullableSteps.subList(0, nullableSteps.size() - 1)
                    : nullableSteps;
        }
    }

    /**
     * A resolved method call.
     *
     * @param javaExpr full call expression, e.g. {@code "q.getTags().size()"}
     * @param kind     {@link ValueKind#PRIM_INT} for size()/length(),
     *                 {@link ValueKind#PRIM_BOOLEAN} or
     *                 {@link ValueKind#BOX_BOOLEAN} for boolean methods
     */
    record Call(String javaExpr, ValueKind kind) {
    }

    /** A literal token, ready to appear in generated Java verbatim. */
    record Literal(Kind kind, String javaText) {

        public enum Kind {
            STRING, NUMBER, BOOLEAN, NULL
        }
    }
}
