package io.github.larkbatis.processor.frontend;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL text into literal text, {@code #{}} tokens and {@code ${}}
 * tokens — a faithful port of MyBatis {@code GenericTokenParser.parse}
 * (mybatis-3 {@code parsing/GenericTokenParser}), applied the way MyBatis
 * applies it:
 * {@code ${}} first (TextSqlNode), then {@code #{}} on the remaining literal
 * text (SqlSourceBuilder).
 *
 * <p>Semantics preserved bug-for-bug:
 * <ul>
 *   <li>{@code \}-escaped open token → literal token text, backslash removed</li>
 *   <li>{@code \}-escaped close token inside an expression → literal {@code }}</li>
 *   <li>unclosed open token → kept as literal text to the end of input</li>
 * </ul>
 */
public final class SqlTokenizer {

    /** One raw token; {@code Hash} is {@code #{}}, {@code Dollar} is {@code ${}}. */
    public sealed interface RawToken {
        record Text(String text) implements RawToken {
        }

        record Hash(String expression) implements RawToken {
        }

        record Dollar(String expression) implements RawToken {
        }
    }

    private SqlTokenizer() {
    }

    public static List<RawToken> tokenize(String sql) {
        List<RawToken> out = new ArrayList<>();
        // pass 1: ${} on the whole text; pass 2: #{} inside the literal parts
        for (RawToken part : split(sql, "${", true)) {
            if (part instanceof RawToken.Text t) {
                out.addAll(split(t.text(), "#{", false));
            } else {
                out.add(part);
            }
        }
        return merge(out);
    }

    /** The GenericTokenParser loop, emitting parts instead of building a string. */
    private static List<RawToken> split(String text, String openToken, boolean dollar) {
        List<RawToken> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        String closeToken = "}";
        int start = text.indexOf(openToken);
        if (start == -1) {
            parts.add(new RawToken.Text(text));
            return parts;
        }
        char[] src = text.toCharArray();
        int offset = 0;
        StringBuilder builder = new StringBuilder();
        StringBuilder expression = null;
        do {
            if (start > 0 && src[start - 1] == '\\') {
                // escaped open token: drop the backslash, keep the token literally
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    expression.setLength(0);
                }
                builder.append(src, offset, start - offset);
                offset = start + openToken.length();
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    if (end <= offset || src[end - 1] != '\\') {
                        expression.append(src, offset, end - offset);
                        break;
                    }
                    // escaped close token inside the expression
                    expression.append(src, offset, end - offset - 1).append(closeToken);
                    offset = end + closeToken.length();
                    end = text.indexOf(closeToken, offset);
                }
                if (end == -1) {
                    // close token was not found: everything from the open token is literal
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    if (builder.length() > 0) {
                        parts.add(new RawToken.Text(builder.toString()));
                        builder.setLength(0);
                    }
                    String expr = expression.toString();
                    parts.add(dollar ? new RawToken.Dollar(expr) : new RawToken.Hash(expr));
                    offset = end + closeToken.length();
                }
            }
            start = text.indexOf(openToken, offset);
        } while (start > -1);
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        if (builder.length() > 0) {
            parts.add(new RawToken.Text(builder.toString()));
        }
        return parts;
    }

    private static List<RawToken> merge(List<RawToken> tokens) {
        List<RawToken> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (RawToken token : tokens) {
            if (token instanceof RawToken.Text t) {
                pending.append(t.text());
            } else {
                if (pending.length() > 0) {
                    out.add(new RawToken.Text(pending.toString()));
                    pending.setLength(0);
                }
                out.add(token);
            }
        }
        if (pending.length() > 0) {
            out.add(new RawToken.Text(pending.toString()));
        }
        return out;
    }
}
