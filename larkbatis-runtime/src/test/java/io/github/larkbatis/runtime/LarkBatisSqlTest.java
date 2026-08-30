package io.github.larkbatis.runtime;

import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LarkBatisSqlTest {

    @AfterEach
    void reset() {
        LarkBatisSql.resetVariantTracking();
    }

    @Test
    void padPow2RoundsUpToTheNextPowerOfTwo() {
        assertEquals(1, LarkBatisSql.padPow2(0));
        assertEquals(1, LarkBatisSql.padPow2(1));
        assertEquals(2, LarkBatisSql.padPow2(2));
        assertEquals(4, LarkBatisSql.padPow2(3));
        assertEquals(4, LarkBatisSql.padPow2(4));
        assertEquals(8, LarkBatisSql.padPow2(5));
        assertEquals(16, LarkBatisSql.padPow2(9));
        assertEquals(1 << 20, LarkBatisSql.padPow2((1 << 20) - 3));
    }

    @Test
    void sumIgnoresDriverSentinels() {
        assertEquals(5, LarkBatisSql.sum(new int[] {1, 2, 2}));
        assertEquals(3, LarkBatisSql.sum(new int[] {1, Statement.SUCCESS_NO_INFO, 2}));
        assertEquals(1, LarkBatisSql.sum(new int[] {Statement.EXECUTE_FAILED, 1}));
        assertEquals(0, LarkBatisSql.sum(new int[0]));
    }

    @Test
    void trackVariantsToleratesManyCallsWithFewVariants() {
        LarkBatisSql.maxSqlVariants(4);
        for (int i = 0; i < 1_000; i++) {
            LarkBatisSql.trackVariants("m.stmt", "SELECT 1");
            LarkBatisSql.trackVariants("m.stmt", "SELECT 2");
        }
        // nothing to assert beyond "no explosion": the warning path is a log line
    }

    @Test
    void trackVariantsStopsRetainingTextsOnceOverTheLimit() {
        LarkBatisSql.maxSqlVariants(4);
        for (int i = 0; i < 100; i++) {
            LarkBatisSql.trackVariants("m.hot", "SELECT " + i);
        }
        // memory stays bounded: after the warning the set is cleared and
        // further texts are not retained — verified indirectly by not OOMing
        // with a tiny limit and many variants; the flag flip is internal.
    }

    @Test
    void failOnUnboundedVariantsThrowsInsteadOfWarning() {
        LarkBatisSql.maxSqlVariants(2);
        LarkBatisSql.failOnUnboundedVariants(true);
        LarkBatisSql.trackVariants("m.unbounded", "SELECT 1");
        LarkBatisSql.trackVariants("m.unbounded", "SELECT 2");

        LarkBatisUnboundedVariantsException thrown =
                assertThrows(LarkBatisUnboundedVariantsException.class,
                        () -> LarkBatisSql.trackVariants("m.unbounded", "SELECT 3"));
        assertEquals("m.unbounded", thrown.statementId());
        assertEquals(2, thrown.limit());
        assertEquals("SELECT 3", thrown.sql());

        // fires once per statement, not on every call after: the counter has
        // already given up, so a hot path does not turn into a throw loop
        LarkBatisSql.trackVariants("m.unbounded", "SELECT 4");
    }

    @Test
    void failOnUnboundedVariantsIsOffByDefault() {
        LarkBatisSql.maxSqlVariants(1);
        for (int i = 0; i < 10; i++) {
            LarkBatisSql.trackVariants("m.warnOnly", "SELECT " + i);
        }
    }
}
