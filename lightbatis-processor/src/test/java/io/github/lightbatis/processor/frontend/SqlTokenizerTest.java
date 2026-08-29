package io.github.lightbatis.processor.frontend;

import io.github.lightbatis.processor.frontend.SqlTokenizer.RawToken;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Port fidelity against MyBatis GenericTokenParser (parsing/GenericTokenParser.java)
 * — escapes and the unclosed-token behavior are the parts nobody remembers.
 */
class SqlTokenizerTest {

    @Test
    void plainTextPassesThrough() {
        assertEquals(List.of(new RawToken.Text("SELECT 1")), SqlTokenizer.tokenize("SELECT 1"));
    }

    @Test
    void hashAndDollarTokensInOrder() {
        assertEquals(List.of(
                new RawToken.Text("SELECT a FROM t WHERE x = "),
                new RawToken.Hash("x"),
                new RawToken.Text(" ORDER BY "),
                new RawToken.Dollar("sort")),
                SqlTokenizer.tokenize("SELECT a FROM t WHERE x = #{x} ORDER BY ${sort}"));
    }

    @Test
    void escapedOpenTokenStaysLiteralWithoutBackslash() {
        // GenericTokenParser: "\\#{}" → literal "#{}" (backslash removed)
        assertEquals(List.of(new RawToken.Text("a #{notAToken} b")),
                SqlTokenizer.tokenize("a \\#{notAToken} b"));
        assertEquals(List.of(new RawToken.Text("a ${notAToken} b")),
                SqlTokenizer.tokenize("a \\${notAToken} b"));
    }

    @Test
    void escapedCloseTokenBelongsToTheExpression() {
        assertEquals(List.of(new RawToken.Hash("a}b")), SqlTokenizer.tokenize("#{a\\}b}"));
    }

    @Test
    void unclosedTokenIsKeptAsLiteralText() {
        assertEquals(List.of(new RawToken.Text("WHERE x = #{x")),
                SqlTokenizer.tokenize("WHERE x = #{x"));
    }

    @Test
    void adjacentTokensProduceNoEmptyTextPieces() {
        assertEquals(List.of(new RawToken.Hash("a"), new RawToken.Hash("b")),
                SqlTokenizer.tokenize("#{a}#{b}"));
    }
}
