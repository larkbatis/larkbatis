package com.example.lbsample;

import io.github.larkbatis.runtime.JdbcLarkBatisSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Custom type handlers against a real driver, from both declaration sites: a
 * mapper XML {@code typeHandler} attribute and {@code @Handler} on the
 * property or the parameter. {@code Money} is outside the type whitelist, so
 * every one of these statements only compiles because a handler was found at
 * build time — and only returns the right value because it is the handler that
 * runs.
 */
class TypeHandlerEndToEndTest {

    private LedgerMapper ledger;
    private WalletMapper wallet;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:th_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE ledger (id BIGINT PRIMARY KEY, amount BIGINT,"
                    + " note VARCHAR(100))");
            st.execute("CREATE TABLE wallet (id BIGINT PRIMARY KEY, balance BIGINT)");
        }
        JdbcLarkBatisSession session = new JdbcLarkBatisSession(dataSource);
        ledger = LarkBatisMappers.ledgerMapper(session);
        wallet = LarkBatisMappers.walletMapper(session);
    }

    /** XML on both ends: typeHandler on the mapping, and inside the #{} that binds. */
    @Test
    void anXmlDeclaredHandlerRoundTrips() {
        assertEquals(1, ledger.insert(1L, new Money(12_345L), "opening"));

        Entry found = ledger.find(1L);
        assertEquals(new Money(12_345L), found.getAmount());
        assertEquals("opening", found.getNote());
    }

    /** SELECT * takes the name-based reader, which must use the same handler. */
    @Test
    void theNameBasedReaderGoesThroughTheHandlerToo() {
        ledger.insert(1L, new Money(100L), "one");
        ledger.insert(2L, new Money(250L), "two");

        List<Entry> all = ledger.all();
        assertEquals(2, all.size());
        assertEquals(new Money(100L), all.get(0).getAmount());
        assertEquals(new Money(250L), all.get(1).getAmount());
    }

    /** The handler owns null in both directions; nothing else gets a say. */
    @Test
    void theHandlerOwnsNull() {
        assertEquals(1, ledger.insert(3L, null, "empty"));

        assertNull(ledger.find(3L).getAmount());
    }

    /** @Handler on the property: the reader and the bind both find it. */
    @Test
    void aPropertyDeclaredHandlerRoundTrips() {
        Wallet w = new Wallet();
        w.setId(7L);
        w.setBalance(new Money(999L));
        assertEquals(1, wallet.insert(w));

        assertEquals(new Money(999L), wallet.find(7L).getBalance());
    }

    /** @Handler on the parameter, binding a type the whitelist would refuse. */
    @Test
    void aParameterDeclaredHandlerBinds() {
        Wallet poor = new Wallet();
        poor.setId(1L);
        poor.setBalance(new Money(50L));
        wallet.insert(poor);

        Wallet rich = new Wallet();
        rich.setId(2L);
        rich.setBalance(new Money(5_000L));
        wallet.insert(rich);

        assertEquals(List.of(2L), wallet.atLeast(new Money(1_000L)));
    }
}
