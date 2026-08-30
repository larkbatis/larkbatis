package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertGolden;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The XML path, end to end through javac: mapper XML found via
 * {@code -Alarkbatis.mapperDir}, dynamic tags folded at build time, and the
 * resulting body shape pinned in the golden file.
 */
class XmlMapperTest {

    private static final String MAPPER_DIR =
            Path.of("src/test/resources/mapper-xml").toAbsolutePath().toString();

    private static final String[] FIXTURES = {
            "com/example/app/User.java",
            "com/example/app/Status.java",
            "com/example/app/UserQuery.java",
            "com/example/app/UserQueryMapper.java",
    };

    @Test
    void dynamicXmlMapperGolden() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir=" + MAPPER_DIR), FIXTURES);
        assertSucceeded(compilation);
        assertGolden("xml/UserQueryMapper$$Impl.java",
                generatedSource(compilation, "com.example.app.UserQueryMapper$$Impl"));
    }

    @Test
    void dynamicXmlMapperIr() {
        IrDumpTest.CapturingProcessor processor = new IrDumpTest.CapturingProcessor();
        Compilation compilation = compileFixturesWith(processor,
                List.of("-Alarkbatis.mapperDir=" + MAPPER_DIR), FIXTURES);
        assertSucceeded(compilation);
        assertEquals(1, processor.dumps.size());
        assertGolden("xml/UserQueryMapper.ir", processor.dumps.get(0));
    }
}
