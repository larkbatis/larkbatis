package io.github.lightbatis.runtime;

/**
 * A statement expected a generated (or {@code <selectKey>}-fetched) key but
 * the driver returned none (design §07).
 */
public class LightBatisNoKeyException extends LightBatisException {

    public LightBatisNoKeyException(String statementId) {
        super("No generated key returned for statement " + statementId, statementId);
    }
}
