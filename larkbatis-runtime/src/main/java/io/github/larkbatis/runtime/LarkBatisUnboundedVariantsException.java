package io.github.larkbatis.runtime;

/**
 * One statement has produced more distinct SQL texts than the configured
 * threshold, and the deployment asked to fail rather than warn
 * ({@code larkbatis.fail-on-unbounded-fragment}).
 *
 * <p>The condition it catches is the operational failure mode of {@code ${}}:
 * driver and database statement caches are keyed by SQL text,
 * so a fragment whose value set is not bounded grows them without limit. The
 * default is still a single warning — a production system should not start
 * throwing because of a log-worthy trend — but a test or staging profile that
 * turns this on finds the unbounded fragment before it ships.
 */
public class LarkBatisUnboundedVariantsException extends LarkBatisException {

    private final String statementId;
    private final int limit;

    public LarkBatisUnboundedVariantsException(String statementId, int limit, String sql) {
        super("LarkBatis statement " + statementId + " has produced more than " + limit
                + " distinct SQL texts; statement caches will keep growing. Prefer"
                + " SqlFragment.allowed(...) or @OrderBy over unbounded fragments.", sql);
        this.statementId = statementId;
        this.limit = limit;
    }

    /** The statement whose SQL text keeps changing, {@code mapperFqn.method}. */
    public String statementId() {
        return statementId;
    }

    /** The variant threshold that was crossed. */
    public int limit() {
        return limit;
    }
}
