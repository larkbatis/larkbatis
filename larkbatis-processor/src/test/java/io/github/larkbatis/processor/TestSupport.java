package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class TestSupport {

    private TestSupport() {
    }

    static Compilation compileFixtures(String... fixtureFiles) {
        return compileFixturesWith(new LarkBatisProcessor(), fixtureFiles);
    }

    static Compilation compileFixturesWith(Processor processor, String... fixtureFiles) {
        return compileFixturesWith(processor, java.util.List.of(), fixtureFiles);
    }

    /** The mapper XML tests pass {@code -Alarkbatis.mapperDir=...} here. */
    static Compilation compileFixturesWith(Processor processor, java.util.List<String> options,
            String... fixtureFiles) {
        return Compiler.javac()
                .withProcessors(processor)
                .withOptions(options)
                .compile(Arrays.stream(fixtureFiles)
                        .map(f -> JavaFileObjects.forResource("fixtures/" + f))
                        .toList());
    }

    static void assertSucceeded(Compilation compilation) {
        assertEquals(Compilation.Status.SUCCESS, compilation.status(),
                () -> "compilation failed:\n" + compilation.diagnostics().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("\n")));
    }

    static void assertFailedWith(Compilation compilation, String messageFragment) {
        assertEquals(Compilation.Status.FAILURE, compilation.status(),
                "expected a compile error mentioning: " + messageFragment);
        String errors = messagesOf(compilation, Diagnostic.Kind.ERROR);
        assertTrue(errors.contains(messageFragment),
                () -> "expected an error mentioning \"" + messageFragment + "\" but got:\n" + errors);
    }

    static String messagesOf(Compilation compilation, Diagnostic.Kind kind) {
        return compilation.diagnostics().stream()
                .filter(d -> d.getKind() == kind
                        || (kind == Diagnostic.Kind.WARNING && d.getKind() == Diagnostic.Kind.MANDATORY_WARNING))
                .map(d -> d.getMessage(null))
                .collect(Collectors.joining("\n"));
    }

    static String generatedSource(Compilation compilation, String fqn) {
        JavaFileObject file = compilation.generatedSourceFile(fqn)
                .orElseGet(() -> fail("no generated source " + fqn + "; generated: "
                        + compilation.generatedSourceFiles().stream()
                                .map(JavaFileObject::getName)
                                .collect(Collectors.joining(", "))));
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Golden-file assertion: unexpected emitter changes show
     * up as a readable diff. Refresh with {@code ./gradlew test -Pupdate-golden}.
     */
    static void assertGolden(String relativePath, String actual) {
        Path file = Path.of(System.getProperty("larkbatis.golden.dir")).resolve(relativePath);
        try {
            if (Boolean.getBoolean("larkbatis.golden.update")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual);
                return;
            }
            assertTrue(Files.exists(file),
                    "golden file missing: " + file + " — create it with ./gradlew test -Pupdate-golden");
            assertEquals(Files.readString(file), actual, "golden mismatch: " + relativePath
                    + " (if the change is intended: ./gradlew test -Pupdate-golden)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
