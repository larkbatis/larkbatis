package io.github.larkbatis.runtime;

/**
 * {@link LarkBatisTx#commit()} was called on a transaction that an inner
 * scope has already marked rollback-only. Committing here would silently
 * persist half of the work, so it must blow up instead — the same situation
 * Spring reports as {@code UnexpectedRollbackException}.
 */
public class LarkBatisRollbackOnlyException extends LarkBatisException {

    public LarkBatisRollbackOnlyException() {
        super("Transaction is rollback-only: an inner scope exited without committing", "tx:commit");
    }
}
