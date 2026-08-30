package io.github.larkbatis.conformance;

import io.github.larkbatis.conformance.fixtures.ConformancePaymentMapper;
import io.github.larkbatis.conformance.fixtures.ConformancePaymentMapper$$Impl;
import io.github.larkbatis.conformance.fixtures.Payment;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A mapper XML {@code typeHandler} against the oracle. Both frameworks read
 * the same file and both find the same handler class in it — {@code
 * AmountHandler} implements each framework's interface — so what is being
 * compared is purely when and how the handler is reached: MyBatis resolves it
 * through {@code TypeHandlerRegistry} on every column read, LarkBatis names
 * it in the generated reader and calls it directly.
 *
 * <p>Like the nested-result-map suite, this compares the result rather than the
 * SQL: the SELECT is identical either way, and only rows can show whether the
 * value that came back is the same.
 */
class TypeHandlerDifferentialTest {

    private DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:conf_th_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE payment (id BIGINT PRIMARY KEY, amount BIGINT,"
                    + " memo VARCHAR(50))");
            // a NULL amount is the case the two null policies could differ on:
            // MyBatis consults the registry and LarkBatis hands null straight
            // to the handler
            st.execute("INSERT INTO payment VALUES (1, 12345, 'rent'), (2, -50, 'refund'),"
                    + " (3, NULL, 'pending'), (4, 0, 'zero')");
        }
        this.dataSource = h2;
    }

    @Test
    void aHandledColumnMatchesTheOracle() {
        assertSameRows(ConformancePaymentMapper::all);
    }

    @Test
    void aHandledNullMatchesTheOracle() {
        assertSameRows(mapper -> List.of(mapper.find(3)));
    }

    private void assertSameRows(Function<ConformancePaymentMapper, List<Payment>> call) {
        String oracle = describe(DifferentialHarness.mybatisResult(
                dataSource, ConformancePaymentMapper.class, call));
        String actual = describe(DifferentialHarness.larkbatisResult(
                dataSource, ConformancePaymentMapper$$Impl::new, call));
        assertEquals(oracle, actual);
    }

    /** A rendering, not equals: the fixtures have none, and a diff has to be readable. */
    private static String describe(List<Payment> payments) {
        return payments.stream()
                .map(p -> "payment " + p.getId()
                        + " amount=" + (p.getAmount() == null ? "null" : p.getAmount().cents())
                        + " memo=" + p.getMemo())
                .collect(Collectors.joining("\n"));
    }
}
