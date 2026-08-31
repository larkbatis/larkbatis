package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static io.github.larkbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Alarkbatis.typeHandlers}, the build-time answer to a
 * {@code mybatis-config.xml} {@code <typeHandlers>} block: a default handler
 * per Java type, so a migrating codebase does not have to put {@code @Handler}
 * on every site that already worked.
 *
 * <p>Nothing is scanned and nothing is looked up at runtime. The entry is
 * checked during {@code javac} and the handler it names is compiled into the
 * reader as a direct call on a field of the handler's own class — the same
 * shape {@code @Handler} produces, reached a different way.
 */
class TypeHandlerDefaultsTest {

    private static final String[] FIXTURES = {
            "com/example/app/Money.java",
            "com/example/app/MoneyHandler.java",
            "com/example/app/Payment.java",
            "com/example/app/PaymentMapper.java",
    };

    private static final String REGISTERED =
            "-Alarkbatis.typeHandlers=com.example.app.Money:com.example.app.MoneyHandler";

    private static Compilation compile(String... options) {
        return compileFixturesWith(new LarkBatisProcessor(), List.of(options), FIXTURES);
    }

    // --- what the registry is for ------------------------------------------------

    /**
     * Without it, an unannotated {@code Money} property is exactly the error
     * it was before: the codec has no strategy for the type and nothing named
     * a handler.
     */
    @Test
    void withoutAnEntryAnUnannotatedPropertyIsStillRejected() {
        assertFailedWith(compile(), "has unsupported type com.example.app.Money");
    }

    @Test
    void aRegisteredTypeReachesTheGeneratedReader() {
        Compilation compilation = compile(REGISTERED);
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.PaymentRow");
        assertTrue(reader.contains("private static final MoneyHandler H0 = new MoneyHandler()"),
                reader);
        assertTrue(reader.contains("H0.read(rs"), reader);
    }

    /** The same entry moves a {@code #{}} parameter, bound directly and nested. */
    @Test
    void aRegisteredTypeMovesBoundParametersToo() {
        Compilation compilation = compile(REGISTERED);
        assertSucceeded(compilation);

        String impl = generatedSource(compilation, "com.example.app.PaymentMapper$$Impl");
        assertTrue(impl.contains("private static final MoneyHandler H0 = new MoneyHandler()"),
                impl);
        // #{amount} — the parameter is the value
        assertTrue(impl.contains("H0.write(ps, 1, amount)"), impl);
        // #{p.amount} — one property level down
        assertTrue(impl.contains("H0.write(ps, 2, p.getAmount())"), impl);
    }

    /**
     * Naming a handler at the site stays the more specific answer, for the same
     * reason a {@code <resultMap>} beats {@code @Column}.
     */
    @Test
    void anAnnotationAtTheSiteStillWins() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of(REGISTERED),
                "com/example/app/Money.java",
                "com/example/app/MoneyHandler.java",
                "com/example/app/AltMoneyHandler.java",
                "com/example/app/AnnotatedPayment.java",
                "com/example/app/AnnotatedPaymentMapper.java");
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.AnnotatedPaymentRow");
        assertTrue(reader.contains("new AltMoneyHandler()"), reader);
        assertFalse(reader.contains("new MoneyHandler()"),
                "the registry must not override the @Handler at the site: " + reader);
    }

    // --- the entries themselves ---------------------------------------------------

    @Test
    void aMalformedEntryIsAnError() {
        assertFailedWith(compile("-Alarkbatis.typeHandlers=com.example.app.Money"),
                "is not <javaType>:<handlerClass>");
    }

    @Test
    void anUnknownJavaTypeIsAnError() {
        assertFailedWith(compile("-Alarkbatis.typeHandlers=com.example.Nope:"
                        + "com.example.app.MoneyHandler"),
                "names java type com.example.Nope, which is not on the compilation classpath");
    }

    @Test
    void anUnknownHandlerIsAnError() {
        assertFailedWith(compile("-Alarkbatis.typeHandlers=com.example.app.Money:com.example.Nope"),
                "names handler com.example.Nope, which is not on the compilation classpath");
    }

    /**
     * The same rules {@code @Handler} is held to, named after the option
     * instead of after an annotation there is no element to point at.
     */
    @Test
    void aHandlerThatDoesNotHandleThatTypeIsAnError() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.typeHandlers=com.example.app.Money:"
                        + "com.example.app.UpperHandler"),
                "com/example/app/Money.java",
                "com/example/app/MoneyHandler.java",
                "com/example/app/UpperHandler.java",
                "com/example/app/Payment.java",
                "com/example/app/PaymentMapper.java");

        assertFailedWith(compilation,
                "larkbatis.typeHandlers entry com.example.app.Money:com.example.app.UpperHandler:"
                        + " com.example.app.UpperHandler handles java.lang.String, but the value"
                        + " is com.example.app.Money");
    }

    @Test
    void twoHandlersForOneTypeIsAnError() {
        assertFailedWith(compile("-Alarkbatis.typeHandlers=com.example.app.Money:"
                        + "com.example.app.MoneyHandler,com.example.app.Money:"
                        + "com.example.app.UpperHandler"),
                "one type, one handler");
    }

    /**
     * A registered type nothing in the build ever has is what a typo in the
     * java-type half looks like: no property changes, no error, no handler.
     * The warning is the only thing that would ever say so.
     */
    @Test
    void anEntryThatMovedNothingIsReported() {
        Compilation compilation = compile(REGISTERED
                + ",java.util.UUID:com.example.app.MoneyHandler");

        String warnings = messagesOf(compilation, Diagnostic.Kind.WARNING);
        assertTrue(warnings.contains("registers a handler for java.util.UUID"), warnings);
        assertFalse(warnings.contains("for com.example.app.Money,"),
                "the used entry must not be reported: " + warnings);
    }

    @Test
    void aUsedEntryIsNotReported() {
        assertFalse(messagesOf(compile(REGISTERED), Diagnostic.Kind.WARNING)
                .contains("moved nothing"));
    }
}
