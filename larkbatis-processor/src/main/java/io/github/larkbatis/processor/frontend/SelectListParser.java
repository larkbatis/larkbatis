package io.github.larkbatis.processor.frontend;

import io.github.larkbatis.processor.ir.SqlPiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether the generator controls a SELECT's column order.
 * Deliberately conservative: an unparsed select list
 * downgrades that one statement to the name-based reader — always correct,
 * just one metadata pass slower — and the downgrade reason is printed at
 * build time.
 */
public final class SelectListParser {

    /** Marker standing in for a {@code ${}} splice while scanning. */
    private static final char DOLLAR_MARK = '\u0001';

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private static final Pattern AS_ALIAS =
            Pattern.compile("(?is)^(.+?)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    /** Bare alias accepted only after a closing paren: {@code COUNT(*) cnt}. */
    private static final Pattern PAREN_ALIAS =
            Pattern.compile("(?s)^(.+\\))\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    private SelectListParser() {
    }

    public sealed interface Result {
        /** Column names (or aliases) in select-list order. */
        record Columns(List<String> names) implements Result {
        }

        record Unparseable(String reason) implements Result {
        }
    }

    public static Result parse(List<SqlPiece> pieces) {
        StringBuilder sb = new StringBuilder();
        for (SqlPiece piece : pieces) {
            if (piece instanceof SqlPiece.Text t) {
                sb.append(t.sql());
            } else if (piece instanceof SqlPiece.Bind) {
                sb.append('?');
            } else {
                sb.append(DOLLAR_MARK);
            }
        }
        String sql = sb.toString().trim();

        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT")) {
            return new Result.Unparseable("statement does not start with SELECT");
        }
        int listStart = "SELECT".length();
        String afterSelect = sql.substring(listStart);
        String afterUpper = upper.substring(listStart);
        for (String modifier : new String[] {" DISTINCT ", " ALL "}) {
            if (afterUpper.startsWith(modifier.stripTrailing())
                    && afterUpper.length() > modifier.length() - 1) {
                int skip = modifier.stripTrailing().length();
                afterSelect = afterSelect.substring(skip);
                afterUpper = afterUpper.substring(skip);
                break;
            }
        }

        int fromIndex = topLevelKeywordIndex(afterSelect, afterUpper, "FROM");
        if (fromIndex < 0) {
            return new Result.Unparseable("no top-level FROM clause found");
        }
        // checked before any trimming: trim() would eat the control-char marker
        if (afterSelect.substring(0, fromIndex).indexOf(DOLLAR_MARK) >= 0) {
            return new Result.Unparseable("${} inside the select list");
        }
        String selectList = afterSelect.substring(0, fromIndex).strip();
        if (selectList.isEmpty()) {
            return new Result.Unparseable("empty select list");
        }

        List<String> names = new ArrayList<>();
        for (String item : splitTopLevel(selectList)) {
            String name = columnNameOf(item.trim());
            if (name == null) {
                return new Result.Unparseable("cannot determine a column name for \"" + item.trim() + "\"");
            }
            names.add(name);
        }
        return new Result.Columns(names);
    }

    /** Index of a keyword at paren depth 0, outside string literals, as a whole word. */
    private static int topLevelKeywordIndex(String text, String upper, String keyword) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\'') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '\'' -> inString = true;
                case '(' -> depth++;
                case ')' -> depth--;
                default -> {
                    if (depth == 0 && upper.startsWith(keyword, i)
                            && (i == 0 || !isWordChar(text.charAt(i - 1)))
                            && (i + keyword.length() == text.length()
                                    || !isWordChar(text.charAt(i + keyword.length())))) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static List<String> splitTopLevel(String text) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\'') {
                    inString = false;
                }
            } else if (ch == '\'') {
                inString = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                items.add(text.substring(start, i));
                start = i + 1;
            }
        }
        items.add(text.substring(start));
        return items;
    }

    /**
     * The column name a driver would report as the label: an explicit alias
     * wins; a plain (possibly qualified) identifier uses its last segment;
     * anything else — {@code *}, expressions without an alias, quoted
     * identifiers — is not worth guessing about.
     */
    private static String columnNameOf(String item) {
        if (item.isEmpty() || item.equals("*") || item.endsWith(".*")) {
            return null;
        }
        Matcher as = AS_ALIAS.matcher(item);
        if (as.matches()) {
            return as.group(2);
        }
        if (IDENTIFIER.matcher(item).matches()) {
            int lastDot = item.lastIndexOf('.');
            return lastDot < 0 ? item : item.substring(lastDot + 1);
        }
        Matcher paren = PAREN_ALIAS.matcher(item);
        if (paren.matches()) {
            return paren.group(2);
        }
        return null;
    }
}
