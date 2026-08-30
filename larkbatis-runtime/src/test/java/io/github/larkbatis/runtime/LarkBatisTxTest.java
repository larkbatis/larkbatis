package io.github.larkbatis.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The twelve transaction cases that {@code LarkBatisTx} has to get right.
 * The suite is the specification; the code is just what makes it green.
 */
class LarkBatisTxTest {

    private final FaultyDataSource dataSource = new FaultyDataSource();
    private final JdbcLarkBatisSession session = new JdbcLarkBatisSession(dataSource);

    /** Test 10 — for EVERY test: no binding may leak past the test body. */
    @AfterEach
    void threadBindingIsEmpty() {
        assertFalse(session.hasActiveTransaction(), "ThreadLocal transaction binding leaked");
    }

    /** Test 1 — the happy path: begin, work, commit. */
    @Test
    void normalCommit() {
        try (LarkBatisTx tx = session.begin()) {
            assertTrue(session.hasActiveTransaction());
            tx.commit();
        }
        FakeConnection c = dataSource.last();
        assertEquals(1, c.commitCount);
        assertEquals(0, c.rollbackCount);
        assertTrue(c.closed);
    }

    /** Test 2 — leaving the scope without commit() must roll back. */
    @Test
    void exitWithoutCommitRollsBack() {
        try (LarkBatisTx tx = session.begin()) {
            // no commit — safe default is rollback
        }
        FakeConnection c = dataSource.last();
        assertEquals(0, c.commitCount);
        assertEquals(1, c.rollbackCount);
        assertTrue(c.closed);
    }

    /** Test 3 — an exception from the scope rolls back and reaches the caller intact. */
    @Test
    void exceptionFromScopeRollsBackAndPropagatesIntact() {
        RuntimeException boom = new RuntimeException("boom");

        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            try (LarkBatisTx tx = session.begin()) {
                throw boom;
            }
        });

        assertSame(boom, caught, "the original exception must reach the caller");
        FakeConnection c = dataSource.last();
        assertEquals(0, c.commitCount);
        assertEquals(1, c.rollbackCount);
        assertTrue(c.closed);
    }

    /** Test 4 — nested begin(): exactly one physical commit, at the outermost scope. */
    @Test
    void nestedScopesCommitExactlyOnce() {
        try (LarkBatisTx outer = session.begin()) {
            try (LarkBatisTx inner = session.begin()) {
                inner.commit();
            }
            outer.commit();
        }
        assertEquals(1, dataSource.connections.size(), "nested scope must reuse the connection");
        FakeConnection c = dataSource.last();
        assertEquals(1, c.commitCount);
        assertEquals(0, c.rollbackCount);
    }

    /** Test 5 — inner scope fails, outer catches and still commits: must blow up, not commit. */
    @Test
    void commitAfterInnerRollbackOnlyThrows() {
        assertThrows(LarkBatisRollbackOnlyException.class, () -> {
            try (LarkBatisTx outer = session.begin()) {
                try {
                    try (LarkBatisTx inner = session.begin()) {
                        throw new RuntimeException("inner failure");
                    }
                } catch (RuntimeException swallowed) {
                    // service code that catches too much — the classic mistake
                }
                outer.commit();
            }
        });
        FakeConnection c = dataSource.last();
        assertEquals(0, c.commitCount, "a rollback-only transaction must never commit");
        assertEquals(1, c.rollbackCount);
        assertTrue(c.closed);
    }

    /** Test 6 — rollback() throws (dead connection): thread unbound, connection still closed. */
    @Test
    void rollbackFailureStillUnbindsThreadAndClosesConnection() {
        LarkBatisException failure = assertThrows(LarkBatisException.class, () -> {
            try (LarkBatisTx tx = session.begin()) {
                dataSource.last().failOnRollback = true;
                // no commit → close() must attempt rollback and blow up
            }
        });

        assertEquals("tx:rollback", failure.sql());
        assertFalse(session.hasActiveTransaction(), "a dead connection must not poison the thread");
        FakeConnection c = dataSource.last();
        assertTrue(c.closed, "connection must be closed even when rollback failed");
    }

    /** Test 7 — cleanup failures must not swallow the original: addSuppressed discipline. */
    @Test
    void cleanupFailureIsSuppressedNotSwallowed() {
        LarkBatisException failure = assertThrows(LarkBatisException.class, () -> {
            try (LarkBatisTx tx = session.begin()) {
                FakeConnection c = dataSource.last();
                c.failOnCommit = true;         // the original failure
                c.failOnSetAutoCommit = true;  // and the cleanup fails too
                tx.commit();
            }
        });

        assertEquals("tx:commit", failure.sql(), "the FIRST failure must be the primary exception");
        assertEquals(1, failure.getSuppressed().length, "cleanup failure must ride along as suppressed");
        assertTrue(failure.getSuppressed()[0] instanceof SQLException);
        assertTrue(dataSource.last().closed);
        assertFalse(session.hasActiveTransaction());
    }

    /** Test 8 — the pool's view: connection state fully restored at close(). */
    @Test
    void connectionStateRestoredWhenReturnedToPool() {
        try (LarkBatisTx tx = session.begin(true)) {
            FakeConnection c = dataSource.last();
            assertFalse(c.autoCommit, "autoCommit off inside the transaction");
            assertTrue(c.readOnly, "readOnly on inside the transaction");
            tx.commit();
        }
        FakeConnection c = dataSource.last();
        assertEquals(Boolean.TRUE, c.autoCommitAtClose, "autoCommit restored before returning to pool");
        assertEquals(Boolean.FALSE, c.readOnlyAtClose, "readOnly restored before returning to pool");
    }

    /** Test 9 — thread-pool simulation: a blown-up task must not haunt the next one. */
    @Test
    void secondTaskOnSameThreadStartsClean() {
        assertThrows(LarkBatisException.class, () -> {
            try (LarkBatisTx tx = session.begin()) {
                dataSource.last().failOnRollback = true;
            }
        });
        FakeConnection first = dataSource.last();

        try (LarkBatisTx tx = session.begin()) {
            tx.commit();
        }

        FakeConnection second = dataSource.last();
        assertNotSame(first, second, "second task must get a fresh connection");
        assertEquals(1, second.commitCount);
        assertEquals(0, second.rollbackCount);
    }

    /** Test 11 — borrowing outside any begin(): auto-commit, closed immediately, no binding. */
    @Test
    void borrowOutsideTransactionIsAutoCommitAndClosesImmediately() {
        Connection c = session.conn();
        FakeConnection fake = dataSource.last();

        assertFalse(session.hasActiveTransaction(), "plain borrow must not touch the ThreadLocal");
        assertTrue(fake.autoCommit);

        session.release(c);
        assertTrue(fake.closed, "released connection must close immediately outside a transaction");
        assertEquals(0, fake.commitCount);
        assertEquals(0, fake.rollbackCount);
    }

    /** Test 12 — two sessions on two DataSources: independent bindings on one thread. */
    @Test
    void twoSessionsOnTwoDataSourcesAreIndependent() {
        FaultyDataSource otherDataSource = new FaultyDataSource();
        JdbcLarkBatisSession otherSession = new JdbcLarkBatisSession(otherDataSource);

        try (LarkBatisTx tx = session.begin()) {
            assertFalse(otherSession.hasActiveTransaction(), "bindings must not bleed across sessions");
            try (LarkBatisTx otherTx = otherSession.begin()) {
                assertTrue(otherSession.hasActiveTransaction());
                otherTx.commit();
            }
            // this session's tx exits WITHOUT commit → rollback
        }

        assertEquals(1, otherDataSource.last().commitCount);
        assertEquals(0, otherDataSource.last().rollbackCount);
        assertEquals(0, dataSource.last().commitCount);
        assertEquals(1, dataSource.last().rollbackCount);
        assertFalse(otherSession.hasActiveTransaction());
    }

    // --- beyond the twelve: edges the twelve imply -------------------------------

    /** conn() inside a transaction hands out the transaction's connection; release is a no-op. */
    @Test
    void borrowInsideTransactionReturnsTheTransactionConnection() {
        try (LarkBatisTx tx = session.begin()) {
            Connection borrowed = session.conn();
            assertSame(dataSource.last().connection, borrowed);
            session.release(borrowed);
            assertFalse(dataSource.last().closed, "release must not close the transaction's connection");
            tx.commit();
        }
        assertTrue(dataSource.last().closed);
    }

    /** begin() failing halfway (setAutoCommit blows up) must close the connection and bind nothing. */
    @Test
    void beginFailureClosesConnectionAndBindsNothing() {
        dataSource.failNextSetAutoCommit = true;

        LarkBatisException failure = assertThrows(LarkBatisException.class, session::begin);

        assertEquals("tx:begin", failure.sql());
        assertTrue(dataSource.last().closed, "half-opened connection must be closed");
        assertFalse(session.hasActiveTransaction());
    }

    /** Explicit rollbackOnly(): commit() after it must throw. */
    @Test
    void explicitRollbackOnlyMakesCommitThrow() {
        assertThrows(LarkBatisRollbackOnlyException.class, () -> {
            try (LarkBatisTx tx = session.begin()) {
                tx.rollbackOnly();
                tx.commit();
            }
        });
        assertEquals(1, dataSource.last().rollbackCount);
        assertEquals(0, dataSource.last().commitCount);
    }
}
