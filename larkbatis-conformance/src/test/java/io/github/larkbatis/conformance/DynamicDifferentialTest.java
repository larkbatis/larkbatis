package io.github.larkbatis.conformance;

import io.github.larkbatis.conformance.fixtures.ConformanceQueryMapper;
import io.github.larkbatis.conformance.fixtures.LarkBatisMappers;
import io.github.larkbatis.conformance.fixtures.Status;
import io.github.larkbatis.conformance.fixtures.UserFilter;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dynamic-SQL differential suite: the same mapper XML runs through MyBatis's
 * DynamicSqlSource at runtime and through the LarkBatis build-time fold, on
 * every branch combination. The oracle's SQL is whitespace-collapsed
 * (shrinkWhitespacesInSql — see DifferentialHarness); ? positions, bind
 * order, setX names and values must match exactly.
 */
class DynamicDifferentialTest {

    private static void assertIdentical(Consumer<ConformanceQueryMapper> call) {
        Recording oracle = DifferentialHarness.mybatis(ConformanceQueryMapper.class, call, true);
        Recording generated = DifferentialHarness.larkbatis(
                LarkBatisMappers::conformanceQueryMapper, call);
        assertEquals(oracle.dump(), generated.dump(),
                "generated dynamic SQL diverged from the MyBatis oracle");
    }

    private static UserFilter filter(Consumer<UserFilter> setup) {
        UserFilter filter = new UserFilter();
        setup.accept(filter);
        return filter;
    }

    // --- <where> + <if> (the landmark) -------------------------------------

    @Test
    void whereWithNoConditions() {
        assertIdentical(mapper -> mapper.search(filter(f -> {
        })));
    }

    @Test
    void whereWithFirstCondition() {
        assertIdentical(mapper -> mapper.search(filter(f -> f.setName("A%"))));
    }

    @Test
    void whereWithSecondConditionAloneStripsTheAnd() {
        assertIdentical(mapper -> mapper.search(filter(f -> f.setMinAge(18))));
    }

    @Test
    void whereWithBothConditions() {
        assertIdentical(mapper -> mapper.search(filter(f -> {
            f.setName("A%");
            f.setMinAge(18);
        })));
    }

    // --- <set> + <if> ------------------------------------------------------------

    @Test
    void setWithBothAssignments() {
        assertIdentical(mapper -> mapper.updateUser(filter(f -> {
            f.setId(7);
            f.setName("Ada");
            f.setEmail("ada@example.com");
        })));
    }

    @Test
    void setWithFirstAssignmentOnlyStripsItsComma() {
        assertIdentical(mapper -> mapper.updateUser(filter(f -> {
            f.setId(7);
            f.setName("Ada");
        })));
    }

    @Test
    void setWithSecondAssignmentOnly() {
        assertIdentical(mapper -> mapper.updateUser(filter(f -> {
            f.setId(7);
            f.setEmail("ada@example.com");
        })));
    }

    @Test
    void setWithNothingToSet() {
        assertIdentical(mapper -> mapper.updateUser(filter(f -> f.setId(7))));
    }

    // --- <choose>/<when>/<otherwise> ------------------------------------------------

    @Test
    void chooseTakesTheWhenBranch() {
        assertIdentical(mapper -> mapper.byStatus(filter(f -> f.setStatus(Status.PAID))));
    }

    @Test
    void chooseFallsBackToOtherwise() {
        assertIdentical(mapper -> mapper.byStatus(filter(f -> {
        })));
    }

    // --- <sql>/<include>, no dynamic tags ----------------------------------------------

    @Test
    void staticXmlStatementWithInclude() {
        assertIdentical(ConformanceQueryMapper::listAll);
    }
}
