package io.github.larkbatis.runtime;

/**
 * A value offered to a {@link SqlFragment} factory (or to a generated
 * {@code @OrderBy} switch) was rejected. This is the runtime edge of the
 * {@code ${}} discipline: the value never reaches the SQL text.
 */
public class LarkBatisRejectedException extends LarkBatisException {

    public LarkBatisRejectedException(String value) {
        super("Rejected SQL fragment value: \"" + value + "\"", null);
    }

    public LarkBatisRejectedException(String value, String[] allowed) {
        super("Rejected SQL fragment value: \"" + value + "\" (allowed: "
                + String.join(", ", allowed) + ")", null);
    }
}
