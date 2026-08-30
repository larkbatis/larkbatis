package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;

/**
 * The XML-path rejections through the full pipeline: everything outside the
 * expression grammar or the supported tag set is a compile error with the fix in the
 */
class XmlMapperFailTest {

    private static Compilation compileAgainst(String badDir) {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir="
                        + Path.of("src/test/resources/mapper-xml-bad/" + badDir)
                                .toAbsolutePath()),
                "com/example/app/User.java",
                "com/example/app/Status.java",
                "com/example/app/UserQuery.java",
                "com/example/app/BadQueryMapper.java");
    }

    @Test
    void ognlTruthinessIsACompileError() {
        // <if test="name"> — LarkBatis wants the comparison spelled out: name != null
        assertFailedWith(compileAgainst("truthiness"), "truthiness was deliberately dropped");
    }

    @Test
    void xmlStatementWithoutAMethodIsACompileError() {
        assertFailedWith(compileAgainst("truthiness"), "\"ghost\" matches no abstract method");
    }

    @Test
    void conditionalInsideForeachIsRejected() {
        // the separator fold assumes every iteration contributes text
        assertFailedWith(compileAgainst("foreach"), "body must always produce SQL");
    }

    @Test
    void generatedKeysOnAForeachInsertPointAtTheBatchForm() {
        assertFailedWith(compileFixturesWith(new LarkBatisProcessor(),
                        List.of("-Alarkbatis.mapperDir="
                                + Path.of("src/test/resources/mapper-xml-bad/foreach-generated-keys")
                                        .toAbsolutePath()),
                        "com/example/app/User.java",
                        "com/example/app/BadKeysMapper.java"),
                "not supported on a <foreach> insert yet");
    }

    @Test
    void padPow2OnAnInsertIsRejected() {
        // repeating the last element would insert duplicate rows
        assertFailedWith(compilePadMapperAgainst("foreach-pad-insert"),
                "@PadPow2 repeats the last element");
    }

    private static Compilation compilePadMapperAgainst(String badDir) {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir="
                        + Path.of("src/test/resources/mapper-xml-bad/" + badDir)
                                .toAbsolutePath()),
                "com/example/app/User.java",
                "com/example/app/BadPadMapper.java");
    }

    @Test
    void padPow2OutsideAnInListIsRejected() {
        // unnest(ARRAY[...]) counts its elements: a repeated last one is a row
        assertFailedWith(compilePadMapperAgainst("foreach-pad-not-in-list"),
                "@PadPow2 applies to an IN list");
    }

    @Test
    void nullableTrueIsRejectedButFalseIsNot() {
        assertFailedWith(compileAgainst("foreach-nullable"),
                "a null collection is always an error");
    }

    @Test
    void foreachItemCollidingWithAParameterIsRejected() {
        // the loop variable takes the XML's name, so a clash would not compile
        assertFailedWith(compileAgainst("foreach-item-collision"),
                "collides with the parameter of the same name");
    }

    @Test
    void foreachOverANonCollectionIsRejected() {
        assertFailedWith(compileAgainst("foreach-not-a-collection"),
                "needs a statically-typed Collection<T>, T[] or Map<K,V>");
    }

    @Test
    void foreachWithAnEmptyBodyIsRejected() {
        assertFailedWith(compileAgainst("foreach-empty-body"), "has an empty body");
    }

    @Test
    void crossMapperIncludeNamesTheNarrowing() {
        // MyBatis resolves dotted refids across mappers; LarkBatis does not
        assertFailedWith(compileAgainst("cross-include"),
                "cross-mapper <include> was narrowed away");
    }

    @Test
    void mapperWithoutXmlNamesTheMissingStatement() {
        // @Mapper interface, no XML found anywhere: the method error says what to add
        assertFailedWith(compileFixturesWith(new LarkBatisProcessor(),
                        List.of(),
                        "com/example/app/User.java",
                        "com/example/app/Status.java",
                        "com/example/app/UserQuery.java",
                        "com/example/app/BadQueryMapper.java"),
                "without a statement annotation");
    }
}
