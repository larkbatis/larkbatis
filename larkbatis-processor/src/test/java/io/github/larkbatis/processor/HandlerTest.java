package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixtures;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Handler} names the class that moves one value, and generated code
 * calls it directly: one shared instance per generated type, no registry and
 * no lookup. Everything the annotation cannot bound — its {@code Class<?>} is
 * unbounded, {@code larkbatis-annotations} having no dependencies to bound it
 * with — is checked here instead, at build time.
 */
class HandlerTest {

    private static final String[] ACCOUNT = {
            "com/example/app/Money.java",
            "com/example/app/MoneyHandler.java",
            "com/example/app/UpperHandler.java",
            "com/example/app/Account.java",
            "com/example/app/AccountMapper.java",
    };

    /** The reader holds one instance per handler class and reads through it. */
    @Test
    void theReaderReadsThroughTheHandler() {
        Compilation compilation = compileFixtures(ACCOUNT);
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.AccountRow");
        assertTrue(reader.contains("private static final MoneyHandler H0 = new MoneyHandler()"),
                () -> "expected a shared MoneyHandler instance:\n" + reader);
        assertTrue(reader.contains("private static final UpperHandler H1 = new UpperHandler()"),
                () -> "expected a shared UpperHandler instance:\n" + reader);
        // the field is typed with the handler's own class, not the interface,
        // so the call site stays monomorphic
        assertTrue(reader.contains("a.setBalance(H0.read(rs, 2))"),
                () -> "expected the handler read at the balance column:\n" + reader);
        assertTrue(reader.contains("a.setOwner(H1.read(rs, 3))"),
                () -> "a handler must win over the codec even for a type it knows:\n" + reader);
        assertTrue(!reader.contains("rs.getString(3)"),
                () -> "the built-in String read must be gone:\n" + reader);
    }

    /** The name-based reader goes through the same instance. */
    @Test
    void theNameBasedReaderUsesTheSameInstance() {
        Compilation compilation = compileFixtures(ACCOUNT);
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.AccountRow");
        assertTrue(reader.contains("a.setBalance(H0.read(rs, c[1]))"),
                () -> "expected the handler in the general read too:\n" + reader);
    }

    /** A bind picks the handler up off the property it reads. */
    @Test
    void theBindWritesThroughTheHandler() {
        Compilation compilation = compileFixtures(ACCOUNT);
        assertSucceeded(compilation);

        String impl = generatedSource(compilation, "com.example.app.AccountMapper$$Impl");
        assertTrue(impl.contains("H0.write(ps, 2, a.getBalance())"),
                () -> "expected the balance bound through the handler:\n" + impl);
        assertTrue(impl.contains("H1.write(ps, 3, a.getOwner())"),
                () -> "expected the owner bound through the handler:\n" + impl);
    }

    /**
     * {@code #{x, typeHandler=...}} is the form a migrated mapper already
     * carries. Before this it was parsed off and dropped, so the statement
     * compiled and bound through the wrong codec in silence.
     */
    @Test
    void anInlineTypeHandlerAttributeIsRead() {
        Compilation compilation = compileFixtures(ACCOUNT);
        assertSucceeded(compilation);

        String impl = generatedSource(compilation, "com.example.app.AccountMapper$$Impl");
        assertTrue(impl.contains("H0.write(ps, 1, floor)"),
                () -> "expected the inline typeHandler to bind the parameter:\n" + impl);
    }

    /** The type argument is the check javac cannot make on an unbounded Class<?>. */
    @Test
    void aHandlerHandlingAnotherTypeIsRejected() {
        assertFailedWith(compileFixtures(
                        "com/example/app/Money.java",
                        "com/example/app/WrongTypeHandler.java",
                        "com/example/app/WrongHandlerAccount.java",
                        "com/example/app/WrongHandlerMapper.java"),
                "handles java.lang.String, but the value is com.example.app.Money");
    }

    /** One instance is shared, so construction arguments have nowhere to come from. */
    @Test
    void aHandlerNeedingConstructorArgumentsIsRejected() {
        assertFailedWith(compileFixtures(
                        "com/example/app/Money.java",
                        "com/example/app/NeedsArgHandler.java",
                        "com/example/app/NeedsArgAccount.java",
                        "com/example/app/NeedsArgMapper.java"),
                "needs a public no-argument constructor");
    }

    /**
     * {@code @Handler} targets METHOD so a getter can carry it. On a mapper
     * method it would mean something else entirely, so it is refused rather
     * than read as if it had been honoured.
     */
    @Test
    void aHandlerOnAMapperMethodIsRejected() {
        assertFailedWith(compileFixtures(
                        "com/example/app/Money.java",
                        "com/example/app/MoneyHandler.java",
                        "com/example/app/MethodHandlerMapper.java"),
                "@Handler on a mapper method is not supported");
    }
}
