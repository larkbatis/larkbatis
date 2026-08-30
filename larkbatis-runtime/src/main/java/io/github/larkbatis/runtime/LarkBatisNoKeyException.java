package io.github.larkbatis.runtime;

/**
 * A statement expected a generated (or {@code <selectKey>}-fetched) key but
 * the driver returned none.
 */
public class LarkBatisNoKeyException extends LarkBatisException {

    public LarkBatisNoKeyException(String statementId) {
        super("No generated key returned for statement " + statementId, statementId);
    }
}
