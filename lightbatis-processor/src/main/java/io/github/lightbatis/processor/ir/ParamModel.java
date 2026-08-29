package io.github.lightbatis.processor.ir;

/**
 * One mapper method parameter, as the generated method signature will declare
 * it.
 *
 * @param name     parameter name in SQL and in the generated signature —
 *                 {@code @Param} value when present, else the source name
 *                 (available from the AST at APT time; no {@code -parameters}
 *                 flag involved, design §08 group 3)
 * @param typeFqn  declared type, e.g. {@code java.util.List<com.x.User>}
 */
public record ParamModel(String name, String typeFqn) {
}
