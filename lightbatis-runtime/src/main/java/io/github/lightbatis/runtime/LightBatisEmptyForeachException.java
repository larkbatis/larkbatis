package io.github.lightbatis.runtime;

/**
 * A {@code <foreach>} collection was empty at execution time, which would
 * produce syntactically invalid SQL such as {@code IN ()} (design §06).
 */
public class LightBatisEmptyForeachException extends LightBatisException {

    public LightBatisEmptyForeachException(String parameterName) {
        super("<foreach> collection \"" + parameterName + "\" is empty", null);
    }
}
