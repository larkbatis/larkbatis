package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertGolden;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static io.github.larkbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-level nested {@code <resultMap>} through javac:
 * a {@code <collection>} and an {@code <association>} filled from a join, plus
 * a flat map whose select list the generator cannot parse.
 */
class ResultMapTest {

    private static final String MAPPER_DIR =
            Path.of("src/test/resources/mapper-xml-resultmap").toAbsolutePath().toString();

    private static final String[] FIXTURES = {
            "com/example/app/Invoice.java",
            "com/example/app/InvoiceLine.java",
            "com/example/app/Customer.java",
            "com/example/app/InvoiceMapper.java",
    };

    private static Compilation compile() {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir=" + MAPPER_DIR), FIXTURES);
    }

    @Test
    void nestedResultMapGolden() {
        Compilation compilation = compile();
        assertSucceeded(compilation);
        assertGolden("resultmap/InvoiceMapper$$Impl.java",
                generatedSource(compilation, "com.example.app.InvoiceMapper$$Impl"));
    }

    @Test
    void nestedResultMapIr() {
        IrDumpTest.CapturingProcessor processor = new IrDumpTest.CapturingProcessor();
        Compilation compilation = compileFixturesWith(processor,
                List.of("-Alarkbatis.mapperDir=" + MAPPER_DIR), FIXTURES);
        assertSucceeded(compilation);
        assertEquals(1, processor.dumps.size());
        assertGolden("resultmap/InvoiceMapper.ir", processor.dumps.get(0));
    }

    @Test
    void theRowReaderSkipsTheNestedProperties() {
        Compilation compilation = compile();
        assertSucceeded(compilation);
        String reader = generatedSource(compilation, "com.example.app.InvoiceRow");
        // lines and customer are filled by the grouping loop, not by a column
        assertTrue(reader.contains("setNumber") && reader.contains("setIssued"));
        assertTrue(!reader.contains("setLines") && !reader.contains("setCustomer"),
                () -> "the reader tried to map a nested property:\n" + reader);
    }

    @Test
    void anUnparseableSelectListDowngradesAndSaysSo() {
        Compilation compilation = compile();
        assertSucceeded(compilation);
        String notes = messagesOf(compilation, javax.tools.Diagnostic.Kind.NOTE);
        assertTrue(notes.contains("InvoiceMapper.findAllFlat") && notes.contains("name-based"),
                () -> "expected a name-based downgrade note for findAllFlat, got:\n" + notes);
    }
}
