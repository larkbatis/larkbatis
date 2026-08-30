package io.github.larkbatis.processor.frontend;

import io.github.larkbatis.processor.frontend.SelectListParser.Result;
import io.github.larkbatis.processor.ir.SqlPiece;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectListParserTest {

    private static Result parse(String sql) {
        List<SqlPiece> pieces = SqlTokenizer.tokenize(sql).stream()
                .map(SelectListParserTest::piece)
                .toList();
        return SelectListParser.parse(pieces);
    }

    private static SqlPiece piece(SqlTokenizer.RawToken token) {
        if (token instanceof SqlTokenizer.RawToken.Text text) {
            return new SqlPiece.Text(text.text());
        }
        if (token instanceof SqlTokenizer.RawToken.Hash h) {
            return new SqlPiece.Bind(h.expression(), h.expression(), ValueKind.PRIM_LONG, null, null);
        }
        SqlTokenizer.RawToken.Dollar d = (SqlTokenizer.RawToken.Dollar) token;
        return new SqlPiece.Dollar(d.expression(),
                SqlPiece.Dollar.DollarKind.CLOSED_VALUE, d.expression(), List.of());
    }

    private static List<String> columns(String sql) {
        Result result = parse(sql);
        assertInstanceOf(Result.Columns.class, result,
                () -> "expected parseable, got: " + result);
        return ((Result.Columns) result).names();
    }

    private static String unparseable(String sql) {
        Result result = parse(sql);
        assertInstanceOf(Result.Unparseable.class, result,
                () -> "expected unparseable, got: " + result);
        return ((Result.Unparseable) result).reason();
    }

    @Test
    void plainColumns() {
        assertEquals(List.of("id", "name", "created_at"),
                columns("SELECT id, name, created_at FROM users WHERE id = #{id}"));
    }

    @Test
    void qualifiedColumnsUseTheLastSegment() {
        assertEquals(List.of("id", "name"), columns("SELECT u.id, u.name FROM users u"));
    }

    @Test
    void aliasesWin() {
        assertEquals(List.of("userName", "cnt"),
                columns("SELECT name AS userName, COUNT(*) cnt FROM users GROUP BY name"));
    }

    @Test
    void distinctIsSkipped() {
        assertEquals(List.of("name"), columns("SELECT DISTINCT name FROM users"));
    }

    @Test
    void subqueryCommasAndInnerFromDoNotConfuseTheScan() {
        assertEquals(List.of("id", "m"),
                columns("SELECT id, (SELECT MAX(x) FROM t2 WHERE a = ',') AS m FROM t1"));
    }

    @Test
    void starIsUnparseable() {
        assertTrue(unparseable("SELECT * FROM users").contains("*"));
        assertTrue(unparseable("SELECT u.* FROM users u").contains("*"));
    }

    @Test
    void expressionWithoutAliasIsUnparseable() {
        assertTrue(unparseable("SELECT price * 2 FROM items").contains("price * 2"));
    }

    @Test
    void dollarInsideSelectListIsUnparseable() {
        assertEquals("${} inside the select list",
                unparseable("SELECT id, ${col} FROM users"));
    }

    @Test
    void dollarAfterFromIsFine() {
        assertEquals(List.of("id"), columns("SELECT id FROM ${table}"));
    }

    @Test
    void noFromClauseIsUnparseable() {
        assertTrue(unparseable("SELECT 1").contains("FROM"));
    }
}
