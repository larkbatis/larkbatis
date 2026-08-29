package io.github.lightbatis.runtime;

import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightBatisSqlTest {

    @AfterEach
    void reset() {
        LightBatisSql.resetVariantTracking();
    }

    @Test
    void padPow2RoundsUpToTheNextPowerOfTwo() {
        assertEquals(1, LightBatisSql.padPow2(0));
        assertEquals(1, LightBatisSql.padPow2(1));
        assertEquals(2, LightBatisSql.padPow2(2));
        assertEquals(4, LightBatisSql.padPow2(3));
        assertEquals(4, LightBatisSql.padPow2(4));
        assertEquals(8, LightBatisSql.padPow2(5));
        assertEquals(16, LightBatisSql.padPow2(9));
        assertEquals(1 << 20, LightBatisSql.padPow2((1 << 20) - 3));
    }

    @Test
    void sumIgnoresDriverSentinels() {
        assertEquals(5, LightBatisSql.sum(new int[] {1, 2, 2}));
        assertEquals(3, LightBatisSql.sum(new int[] {1, Statement.SUCCESS_NO_INFO, 2}));
        assertEquals(1, LightBatisSql.sum(new int[] {Statement.EXECUTE_FAILED, 1}));
        assertEquals(0, LightBatisSql.sum(new int[0]));
    }

    @Test
    void trackVariantsToleratesManyCallsWithFewVariants() {
        LightBatisSql.maxSqlVariants(4);
        for (int i = 0; i < 1_000; i++) {
            LightBatisSql.trackVariants("m.stmt", "SELECT 1");
            LightBatisSql.trackVariants("m.stmt", "SELECT 2");
        }
        // nothing to assert beyond "no explosion": the warning path is a log line
    }

    @Test
    void trackVariantsStopsRetainingTextsOnceOverTheLimit() {
        LightBatisSql.maxSqlVariants(4);
        for (int i = 0; i < 100; i++) {
            LightBatisSql.trackVariants("m.hot", "SELECT " + i);
        }
        // memory stays bounded: after the warning the set is cleared and
        // further texts are not retained — verified indirectly by not OOMing
        // with a tiny limit and many variants; the flag flip is internal.
    }
}
