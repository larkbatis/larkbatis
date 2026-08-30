package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;

/**
 * The {@code <resultMap>} rejections. Every one of these is a shape MyBatis
 * accepts, so the message has to carry the replacement — a migration stops
 * dead on "not supported" and keeps moving on "alias the columns instead".
 */
class ResultMapFailTest {

    private static Compilation compileAgainst(String badDir) {
        return compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir="
                        + Path.of("src/test/resources/mapper-xml-bad/" + badDir).toAbsolutePath()),
                "com/example/app/Invoice.java",
                "com/example/app/InvoiceLine.java",
                "com/example/app/Customer.java",
                "com/example/app/InvoiceMapper.java");
    }

    @Test
    void twoNestedMappingsAreRejected() {
        assertFailedWith(compileAgainst("resultmap-two-nested"),
                "has more than one nested mapping");
    }

    @Test
    void nestingStopsAtOneLevel() {
        assertFailedWith(compileAgainst("resultmap-deep"), "nesting stops at one level");
    }

    @Test
    void aNestedMappingNeedsAnIdToRecogniseALeftJoinMiss() {
        assertFailedWith(compileAgainst("resultmap-no-child-id"),
                "how a LEFT JOIN miss is recognised");
    }

    @Test
    void nestedSelectPointsAtTheJoin() {
        assertFailedWith(compileAgainst("resultmap-nested-select"),
                "which is the N+1 the join is here to avoid");
    }

    @Test
    void discriminatorIsRejectedWithItsReason() {
        assertFailedWith(compileAgainst("resultmap-discriminator"),
                "picks the shape of the result from a value");
    }

    @Test
    void columnPrefixPointsAtAliasing() {
        assertFailedWith(compileAgainst("resultmap-column-prefix"),
                "alias the child columns in the select list");
    }

    @Test
    void mappingACollectionWithResultIsRejected() {
        assertFailedWith(compileAgainst("resultmap-nested-as-result"),
                "filled by <association>/<collection>, not <result>");
    }

    @Test
    void aTypeAliasIsRejectedWithTheFullyQualifiedNameAsked() {
        assertFailedWith(compileAgainst("resultmap-alias"),
                "write the fully-qualified class name");
    }

    @Test
    void aStreamReturnOverANestedMapIsRejected() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapperDir="
                        + Path.of("src/test/resources/mapper-xml-bad/resultmap-stream")
                                .toAbsolutePath()),
                "com/example/app/Invoice.java",
                "com/example/app/InvoiceLine.java",
                "com/example/app/Customer.java",
                "com/example/app/StreamInvoiceMapper.java");
        assertFailedWith(compilation, "only complete once the next parent starts");
    }

    @Test
    void anIdColumnMissingFromTheSelectListIsFatal() {
        // a <result> column that is not selected is only a warning: the
        // property stays null. An <id> column is what the grouping loop reads.
        assertFailedWith(compileAgainst("resultmap-missing-key-column"),
                "which the select list does not contain");
    }
}
