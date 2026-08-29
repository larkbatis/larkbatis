package io.github.lightbatis.runtime;

/**
 * A batch insert asked for generated keys but the driver returned fewer keys
 * than rows. Silently ignoring this would leave part of the batch with null
 * ids and no one the wiser — MyBatis documents the same driver failure mode
 * as {@code MSG_TOO_MANY_KEYS} / issue #1523 (design §07).
 */
public class LightBatisKeyCountMismatchException extends LightBatisException {

    public LightBatisKeyCountMismatchException(String statementId, int expected, int actual) {
        super("Statement " + statementId + " expected " + expected
                + " generated keys but the driver returned " + actual, statementId);
    }
}
