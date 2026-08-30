package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixtures;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @LarkBatisRow} asks for the one thing the escape hatch cannot do
 * without: a generated reader for a class no statement returns. Taking a
 * {@code Class<T>} there instead would put reflection back in the runtime,
 * which is the whole argument.
 */
class LarkBatisRowTest {

    @Test
    void aMarkedClassGetsAReaderWithNoStatementReturningIt() {
        Compilation compilation = compileFixtures("com/example/app/DailyTotal.java");
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.DailyTotalRow");
        assertTrue(reader.contains("RowReader<DailyTotal> READER"),
                () -> "expected a READER constant:\n" + reader);
        assertTrue(reader.contains("d.setDay(JdbcCodec.localDate(rs, 1))"),
                () -> "expected the positional read in declaration order:\n" + reader);
        assertTrue(reader.contains("case \"revenue\":"),
                () -> "expected the name-based fallback:\n" + reader);
    }

    /** The point of the feature: a default method compiles against the generated reader. */
    @Test
    void theEscapeHatchCompilesAgainstTheGeneratedReader() {
        assertSucceeded(compileFixtures(
                "com/example/app/User.java",
                "com/example/app/DailyTotal.java",
                "com/example/app/ReportMapper.java"));
    }

    /** A class that is both a resultType and marked gets one reader, not two. */
    @Test
    void markingAClassAStatementAlreadyReturnsIsHarmless() {
        Compilation compilation = compile(
                JavaFileObjects.forResource("fixtures/com/example/app/DailyTotal.java"),
                JavaFileObjects.forSourceString("com.example.app.DailyMapper", """
                        package com.example.app;

                        import io.github.larkbatis.annotations.Select;
                        import java.util.List;

                        public interface DailyMapper {

                            @Select("SELECT day, revenue FROM daily")
                            List<DailyTotal> all();
                        }
                        """));
        assertSucceeded(compilation);
        assertEquals(1, compilation.generatedSourceFiles().stream()
                        .filter(f -> f.getName().endsWith("DailyTotalRow.java"))
                        .count(),
                "expected exactly one DailyTotalRow");
    }

    /** A record has no setters to fill, and the message says which class is the problem. */
    @Test
    void markingARecordIsACompileError() {
        Compilation compilation = compile(
                JavaFileObjects.forSourceString("com.example.app.Point", """
                        package com.example.app;

                        import io.github.larkbatis.annotations.LarkBatisRow;

                        @LarkBatisRow
                        public record Point(int x, int y) { }
                        """));
        assertFailedWith(compilation, "com.example.app.Point must be a class with setters");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new LarkBatisProcessor()).compile(sources);
    }
}
