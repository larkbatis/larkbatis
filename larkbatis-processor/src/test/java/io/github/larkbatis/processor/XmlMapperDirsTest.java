package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static io.github.larkbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper XML does not have to live under one root. {@code -Alarkbatis.mapperDir}
 * takes a list, which is what lets a build keep rewritten mappers beside the
 * legacy ones, or share a mapper tree between projects — one option rather than
 * several, because a repeated {@code -A} of the same name is the last one javac
 * reads, not the union.
 */
class XmlMapperDirsTest {

    private static final String QUERY_DIR = absolute("mapper-xml");
    private static final String HANDLER_DIR = absolute("mapper-xml-handler");

    /** The union of both directories' fixtures: neither mapper knows the other. */
    private static final String[] FIXTURES = {
            "com/example/app/User.java",
            "com/example/app/Status.java",
            "com/example/app/UserQuery.java",
            "com/example/app/UserQueryMapper.java",
            "com/example/app/Money.java",
            "com/example/app/MoneyHandler.java",
            "com/example/app/UpperHandler.java",
            "com/example/app/Entry.java",
            "com/example/app/LedgerMapper.java",
    };

    private static String absolute(String directory) {
        return Path.of("src/test/resources/" + directory).toAbsolutePath().toString();
    }

    private static Compilation compileWith(String option) {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir=" + option), FIXTURES);
    }

    private static void assertBothMappersGenerated(Compilation compilation) {
        assertSucceeded(compilation);
        assertTrue(generatedSource(compilation, "com.example.app.UserQueryMapper$$Impl")
                .contains("class UserQueryMapper$$Impl"));
        assertTrue(generatedSource(compilation, "com.example.app.LedgerMapper$$Impl")
                .contains("class LedgerMapper$$Impl"));
    }

    @Test
    void readsMappersFromEveryDirectoryInThePathSeparatorList() {
        assertBothMappersGenerated(compileWith(QUERY_DIR + File.pathSeparator + HANDLER_DIR));
    }

    /**
     * A comma is accepted too, because a build file written by hand is where
     * one gets typed and a path separator is easy to get wrong across
     * platforms.
     */
    @Test
    void readsMappersFromACommaSeparatedList() {
        assertBothMappersGenerated(compileWith(QUERY_DIR + "," + HANDLER_DIR));
    }

    /** A blank entry is a trailing separator, not a request to scan nothing. */
    @Test
    void ignoresBlankEntries() {
        assertBothMappersGenerated(compileWith(
                QUERY_DIR + File.pathSeparator + File.pathSeparator + HANDLER_DIR
                        + File.pathSeparator));
    }

    /**
     * A mistyped directory generates nothing and would otherwise say nothing,
     * which reads exactly like a mapper XML the processor decided to skip.
     */
    @Test
    void warnsAboutADirectoryThatDoesNotExist() {
        Compilation compilation = compileWith(
                QUERY_DIR + File.pathSeparator + absolute("mapper-xml-typo")
                        + File.pathSeparator + HANDLER_DIR);

        assertBothMappersGenerated(compilation);
        assertTrue(messagesOf(compilation, Diagnostic.Kind.WARNING)
                        .contains("entry is not a directory"),
                messagesOf(compilation, Diagnostic.Kind.WARNING));
    }

    /**
     * Two directories declaring one namespace is an error rather than a
     * last-one-wins merge: the two files disagree about the same mapper and
     * nothing here can say which one the build meant. It is also why the build
     * plugins drop a directory named twice before the option is built — the
     * same tree listed twice would otherwise land here.
     */
    @Test
    void rejectsOneNamespaceDeclaredInTwoDirectories() {
        assertFailedWith(compileWith(QUERY_DIR + File.pathSeparator + QUERY_DIR),
                "both declare namespace com.example.app.UserQueryMapper");
    }
}
