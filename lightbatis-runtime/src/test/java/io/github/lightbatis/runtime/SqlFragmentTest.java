package io.github.lightbatis.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFragmentTest {

    @Test
    void allowedAcceptsWhitelistedValueAndReturnsTheWhitelistInstance() {
        SqlFragment f = SqlFragment.allowed("name", "id", "name", "created_at");
        assertEquals("name", f.text());
    }

    @Test
    void allowedRejectsAnythingOutsideTheWhitelist() {
        LightBatisRejectedException e = assertThrows(LightBatisRejectedException.class,
                () -> SqlFragment.allowed("name; DROP TABLE users", "id", "name"));
        assertEquals(true, e.getMessage().contains("id, name"));
    }

    @Test
    void identifierAcceptsPlainAndQualifiedIdentifiers() {
        assertEquals("users", SqlFragment.identifier("users").text());
        assertEquals("public.users", SqlFragment.identifier("public.users").text());
        assertEquals("_tmp_1", SqlFragment.identifier("_tmp_1").text());
    }

    @Test
    void identifierRejectsEverythingElse() {
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier("users; --"));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier("a.b.c"));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier("1users"));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier(""));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier(null));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.identifier("na me"));
    }

    @Test
    void noFactoryProducesAnEmptyFragment() {
        // design §08: ORDER BY ${sort} with a null/empty value must fail at the
        // call site, not become "ORDER BY " and blow up inside the database
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.unsafeRawSql(null));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.unsafeRawSql(""));
        assertThrows(LightBatisRejectedException.class, () -> SqlFragment.unsafeRawSql("   "));
    }

    @Test
    void unsafeRawSqlPassesArbitraryTextThrough() {
        assertEquals("ORDER BY LENGTH(name) DESC",
                SqlFragment.unsafeRawSql("ORDER BY LENGTH(name) DESC").text());
    }
}
