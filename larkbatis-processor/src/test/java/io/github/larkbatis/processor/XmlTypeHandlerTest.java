package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mapper XML {@code typeHandler} is read where MyBatis reads one: on
 * {@code <id>}/{@code <result>}, and inside a {@code #{}}. This is the form a
 * migrated mapper already carries, so the bean it maps onto stays untouched —
 * {@code Entry} has no LarkBatis annotation on it at all.
 */
class XmlTypeHandlerTest {

    private static final String MAPPER_DIR =
            Path.of("src/test/resources/mapper-xml-handler").toAbsolutePath().toString();

    private static Compilation compile() {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir=" + MAPPER_DIR),
                "com/example/app/Money.java",
                "com/example/app/MoneyHandler.java",
                "com/example/app/UpperHandler.java",
                "com/example/app/Entry.java",
                "com/example/app/LedgerMapper.java");
    }

    /**
     * {@code Money} is outside the type whitelist, so this also proves the
     * handler is known when the result model is built — merging it afterwards
     * would mean the property had already been rejected.
     */
    @Test
    void aResultMappingHandlerReachesTheReader() {
        Compilation compilation = compile();
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.EntryRow");
        assertTrue(reader.contains("private static final MoneyHandler H0 = new MoneyHandler()"),
                () -> "expected a shared MoneyHandler instance:\n" + reader);
        assertTrue(reader.contains("e.setAmount(H0.read(rs, 2))"),
                () -> "expected the amount read through the handler:\n" + reader);
        assertTrue(reader.contains("e.setNote(H1.read(rs, 3))"),
                () -> "expected the note read through the handler:\n" + reader);
    }

    /** The same attribute inside a {@code #{}}, which used to be dropped. */
    @Test
    void anInlineTypeHandlerInXmlSqlIsRead() {
        Compilation compilation = compile();
        assertSucceeded(compilation);

        String impl = generatedSource(compilation, "com.example.app.LedgerMapper$$Impl");
        assertTrue(impl.contains("H0.write(ps, 2, amount)"),
                () -> "expected the inline typeHandler to bind the parameter:\n" + impl);
    }
}
