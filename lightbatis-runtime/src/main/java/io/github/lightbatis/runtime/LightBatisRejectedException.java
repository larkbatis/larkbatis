package io.github.lightbatis.runtime;

/**
 * A value offered to a {@link SqlFragment} factory (or to a generated
 * {@code @OrderBy} switch) was rejected. This is the runtime edge of the
 * {@code ${}} discipline (design §08): the value never reaches the SQL text.
 */
public class LightBatisRejectedException extends LightBatisException {

    public LightBatisRejectedException(String value) {
        super("Rejected SQL fragment value: \"" + value + "\"", null);
    }

    public LightBatisRejectedException(String value, String[] allowed) {
        super("Rejected SQL fragment value: \"" + value + "\" (allowed: "
                + String.join(", ", allowed) + ")", null);
    }
}
