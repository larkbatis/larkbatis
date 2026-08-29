package io.github.lightbatis.runtime;

import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Small static helpers referenced by generated code. Nothing here inspects
 * types or shapes — that all happened at build time.
 */
public final class LightBatisSql {

    private static final Logger LOG = Logger.getLogger(LightBatisSql.class.getName());

    private static final int DEFAULT_MAX_SQL_VARIANTS = 64;

    private static volatile int maxSqlVariants =
            Integer.getInteger("lightbatis.maxSqlVariants", DEFAULT_MAX_SQL_VARIANTS);

    private static final ConcurrentHashMap<String, VariantCounter> VARIANTS = new ConcurrentHashMap<>();

    private LightBatisSql() {
    }

    /**
     * Counts distinct SQL texts produced by one statement. The generator
     * emits this call for every statement containing {@code ${}} (design §08):
     * statement caches — the driver's and the database's — are keyed by the
     * SQL text, so an unbounded number of variants is an operational failure
     * mode. You find out through one log line instead of an incident.
     */
    public static void trackVariants(String statementId, String sql) {
        VariantCounter counter = VARIANTS.computeIfAbsent(statementId, k -> new VariantCounter());
        if (counter.warned) {
            return;
        }
        counter.texts.add(sql);
        int limit = maxSqlVariants;
        if (counter.texts.size() > limit && !counter.warned) {
            counter.warned = true;
            counter.texts.clear(); // stop retaining texts once the warning fired
            LOG.warning(() -> "LightBatis statement " + statementId + " has produced more than "
                    + limit + " distinct SQL texts; statement caches will keep growing. "
                    + "Prefer SqlFragment.allowed(...) or @OrderBy over unbounded fragments.");
        }
    }

    /** Warning threshold for {@link #trackVariants}; default 64. */
    public static void maxSqlVariants(int limit) {
        maxSqlVariants = limit;
    }

    /**
     * Next power of two {@code >= n}, used by the optional {@code <foreach>}
     * placeholder padding (design §06). Padding bounds the number of SQL
     * variants at log2(n) instead of n.
     */
    public static int padPow2(int n) {
        if (n <= 1) {
            return 1;
        }
        if (n > (1 << 30)) {
            return n;
        }
        return Integer.highestOneBit(n - 1) << 1;
    }

    /**
     * Sum of batch update counts. {@link Statement#SUCCESS_NO_INFO} and
     * {@link Statement#EXECUTE_FAILED} contribute zero.
     */
    public static int sum(int[] updateCounts) {
        int total = 0;
        for (int count : updateCounts) {
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    /** Test hook: forget everything {@link #trackVariants} has seen. */
    static void resetVariantTracking() {
        VARIANTS.clear();
        maxSqlVariants = Integer.getInteger("lightbatis.maxSqlVariants", DEFAULT_MAX_SQL_VARIANTS);
    }

    private static final class VariantCounter {
        final Set<String> texts = ConcurrentHashMap.newKeySet();
        volatile boolean warned;
    }
}
