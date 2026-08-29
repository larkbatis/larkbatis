package io.github.lightbatis.runtime;

/**
 * {@link LightBatisTx#commit()} was called on a transaction that an inner
 * scope has already marked rollback-only. Committing here would silently
 * persist half of the work, so it must blow up instead — the same situation
 * Spring reports as {@code UnexpectedRollbackException}.
 */
public class LightBatisRollbackOnlyException extends LightBatisException {

    public LightBatisRollbackOnlyException() {
        super("Transaction is rollback-only: an inner scope exited without committing", "tx:commit");
    }
}
