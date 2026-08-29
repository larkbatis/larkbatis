package io.github.lightbatis.conformance;

import io.github.lightbatis.conformance.fixtures.ConformanceUserMapper;
import io.github.lightbatis.conformance.fixtures.LightBatisMappers;
import io.github.lightbatis.conformance.fixtures.User;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The differential suite (build plan §03): MyBatis is the oracle; the SQL
 * string, the setX order, and the JDBC type of every parameter must match
 * char for char. This is the project's most important test asset — every
 * emitter change answers to it.
 */
class DifferentialTest {

    /** Both sides run the same call; their recordings must be identical. */
    private static void assertIdentical(Consumer<ConformanceUserMapper> call) {
        Recording oracle = DifferentialHarness.mybatis(ConformanceUserMapper.class, call);
        Recording generated = DifferentialHarness.lightbatis(
                LightBatisMappers::conformanceUserMapper, call);
        assertEquals(oracle.dump(), generated.dump(),
                "generated code diverged from the MyBatis oracle");
    }

    @Test
    void selectWithSingleScalarParam() {
        assertIdentical(mapper -> mapper.findById(7));
    }

    @Test
    void selectWithMultipleNamedParams() {
        assertIdentical(mapper -> mapper.search("A%", 18));
    }

    @Test
    void insertWithGeneratedKeys() {
        assertIdentical(mapper -> {
            User u = new User();
            u.setName("Ada");
            u.setAge(36);
            u.setEmail("ada@example.com");
            u.setCreatedAt(Instant.parse("2026-08-30T10:15:30Z"));
            mapper.insert(u);
        });
    }

    @Test
    void updateWithNamedParams() {
        assertIdentical(mapper -> mapper.updateEmail(7, "new@example.com"));
    }

    @Test
    void delete() {
        assertIdentical(mapper -> mapper.deleteByAge(18));
    }

    /**
     * Build plan §09, week-one task 3: see the two SQL strings side by side.
     * Not an assertion — the visible starting point of every future diff.
     */
    @Test
    void printBothSides() {
        Consumer<ConformanceUserMapper> call = mapper -> mapper.findById(7);
        System.out.println("=== MyBatis (oracle) ===");
        System.out.print(DifferentialHarness.mybatis(ConformanceUserMapper.class, call).dump());
        System.out.println("=== LightBatis (generated) ===");
        System.out.print(DifferentialHarness.lightbatis(
                LightBatisMappers::conformanceUserMapper, call).dump());
    }
}
