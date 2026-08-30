package io.github.larkbatis.processor.frontend.expr;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.expr.ExprTypes.Call;
import io.github.larkbatis.processor.frontend.expr.ExprTypes.Literal;
import io.github.larkbatis.processor.frontend.expr.ExprTypes.Value;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the narrow {@code <if test>} grammar into a Java
 * boolean expression over the mapper method's parameters. Everything outside
 * the grammar is a compile error naming the offending token — MyBatis OGNL
 * truthiness is deliberately not reproduced: {@code test="count"} and
 * {@code test="user"} are rejected with the fix spelled out.
 *
 * <p>Null semantics, fixed and documented instead of OGNL's coercions:
 * <ul>
 *   <li>{@code == null} / {@code != null} translate to null-propagating
 *       chains over every reference step of the path, matching OGNL's
 *       null-safe navigation exactly.</li>
 *   <li>Every other comparison is guarded: a null anywhere along either
 *       operand's path makes the comparison {@code false}, and {@code a != b}
 *       is exactly {@code !(a == b)}. This deliberately diverges from OGNL's
 *       null-as-zero numeric coercion ({@code null <= 18} is true in MyBatis)
 *       — that coercion is the same ambiguity the grammar rejects for truthiness.</li>
 *   <li>A method call on a null receiver evaluates to {@code false} (MyBatis
 *       throws instead; a boolean condition that crashes is not worth
 *       keeping compatible).</li>
 * </ul>
 */
public final class ExprCompiler {

    private ExprCompiler() {
    }

    /** Compiles {@code test} to a Java boolean expression, or throws. */
    public static String compileBoolean(String test, ExprTypes types) {
        List<Tok> tokens = lex(test);
        Parser parser = new Parser(test, tokens);
        Node ast = parser.parseOr();
        parser.expect(TokKind.EOF);
        return new Translator(test, types).booleanOf(ast, Set.of()).text();
    }

    /**
     * Syntax-only check of {@code test} against the grammar — lex and
     * parse, no typing. For the places where no interface types exist yet:
     * the mybatis-3 XML corpus sweep and the legacy-mapper scanner.
     * Anything accepted here still goes through {@link #compileBoolean} for
     * the typed rules.
     *
     * @param barePath a bare property operand occurs ({@code test="count"}):
     *     its typed fate is either a boolean property (accepted) or OGNL
     *     truthiness (rejected) — undecidable without the parameter types
     * @param valueCalls calls the grammar refuses whatever the receiver is,
     *     because they answer with a value rather than a condition —
     *     {@code name.trim() != ''} is the one every legacy mapper has
     * @param untypedCalls calls whose fate depends on a return type this
     *     check cannot see: accepted if boolean, refused otherwise
     */
    public record GrammarCheck(boolean barePath, List<String> valueCalls,
                               List<String> untypedCalls) {
    }

    /**
     * Calls the grammar accepts on any receiver: {@code size()} and
     * {@code length()} answer with an int, {@code isEmpty()} with a boolean.
     */
    private static final Set<String> ALWAYS_ACCEPTED = Set.of("size", "length", "isEmpty");

    /**
     * Calls that answer with a value on every type that has them, so the
     * grammar refuses them without needing to know the receiver. Not a
     * complete list and cannot be — it is the set that shows up in real
     * {@code test} attributes, which is what a scan of someone else's
     * codebase needs it to cover.
     */
    private static final Set<String> VALUE_CALLS = Set.of(
            "trim", "strip", "toString", "substring", "toLowerCase", "toUpperCase",
            "indexOf", "lastIndexOf", "charAt", "concat", "replace", "split", "join",
            "get", "getKey", "getValue", "keySet", "values", "entrySet", "iterator",
            "name", "ordinal", "valueOf", "format", "hashCode", "getClass", "getTime",
            "intValue", "longValue", "doubleValue", "floatValue", "byteValue", "shortValue",
            "toArray", "stream");

    /**
     * A prefix convention strong enough to trust in a report: a no-argument
     * {@code isX}/{@code hasX} is a boolean getter in practically every Java
     * codebase, and flagging those would bury the calls that really do need
     * a decision.
     */
    private static boolean readsAsBoolean(String name) {
        return name.startsWith("is") || name.startsWith("has") || name.startsWith("can")
                || name.startsWith("should");
    }

    public static GrammarCheck checkGrammar(String test) {
        List<Tok> tokens = lex(test);
        Parser parser = new Parser(test, tokens);
        Node ast = parser.parseOr();
        parser.expect(TokKind.EOF);
        List<String> calls = new ArrayList<>();
        collectCalls(ast, calls);
        List<String> valueCalls = new ArrayList<>();
        List<String> untypedCalls = new ArrayList<>();
        for (String call : calls) {
            if (ALWAYS_ACCEPTED.contains(call)) {
                continue;
            }
            if (VALUE_CALLS.contains(call)) {
                valueCalls.add(call);
            } else if (!readsAsBoolean(call)) {
                untypedCalls.add(call);
            }
        }
        return new GrammarCheck(hasBarePath(ast), List.copyOf(valueCalls),
                List.copyOf(untypedCalls));
    }

    /** Every method name the expression calls, in source order. */
    private static void collectCalls(Node node, List<String> out) {
        if (node instanceof OrNode or) {
            collectCalls(or.left(), out);
            collectCalls(or.right(), out);
        } else if (node instanceof AndNode and) {
            collectCalls(and.left(), out);
            collectCalls(and.right(), out);
        } else if (node instanceof NotNode not) {
            collectCalls(not.inner(), out);
        } else if (node instanceof RelNode rel) {
            collectCall(rel.left(), out);
            collectCall(rel.right(), out);
        }
    }

    private static void collectCall(Operand operand, List<String> out) {
        if (operand instanceof PathOp path && path.methodName() != null) {
            out.add(path.methodName());
        }
    }

    private static boolean hasBarePath(Node node) {
        if (node instanceof OrNode or) {
            return hasBarePath(or.left()) || hasBarePath(or.right());
        }
        if (node instanceof AndNode and) {
            return hasBarePath(and.left()) || hasBarePath(and.right());
        }
        if (node instanceof NotNode not) {
            return hasBarePath(not.inner());
        }
        RelNode rel = (RelNode) node;
        return rel.op() == null
                && rel.left() instanceof PathOp path
                && path.methodName() == null;
    }

    private static LarkBatisProcessingException reject(String test, String message) {
        return new LarkBatisProcessingException(null,
                "test=\"" + test + "\": " + message);
    }

    // --- lexer -----------------------------------------------------------------

    private enum TokKind {
        IDENT, STRING, NUMBER, TRUE, FALSE, NULL,
        LPAREN, RPAREN, DOT, COMMA,
        EQ, NEQ, LT, LTE, GT, GTE, AND, OR, NOT, EOF
    }

    private record Tok(TokKind kind, String text, int pos) {
    }

    private static List<Tok> lex(String test) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = test.length();
        while (i < n) {
            char ch = test.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            int start = i;
            switch (ch) {
                case '(' -> { out.add(new Tok(TokKind.LPAREN, "(", i)); i++; }
                case ')' -> { out.add(new Tok(TokKind.RPAREN, ")", i)); i++; }
                case '.' -> { out.add(new Tok(TokKind.DOT, ".", i)); i++; }
                case ',' -> { out.add(new Tok(TokKind.COMMA, ",", i)); i++; }
                case '=' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '=') {
                        out.add(new Tok(TokKind.EQ, "==", i));
                        i += 2;
                    } else {
                        throw reject(test, "assignment is not an expression; use ==");
                    }
                }
                case '!' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '=') {
                        out.add(new Tok(TokKind.NEQ, "!=", i));
                        i += 2;
                    } else {
                        out.add(new Tok(TokKind.NOT, "!", i));
                        i++;
                    }
                }
                case '<' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '=') {
                        out.add(new Tok(TokKind.LTE, "<=", i));
                        i += 2;
                    } else {
                        out.add(new Tok(TokKind.LT, "<", i));
                        i++;
                    }
                }
                case '>' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '=') {
                        out.add(new Tok(TokKind.GTE, ">=", i));
                        i += 2;
                    } else {
                        out.add(new Tok(TokKind.GT, ">", i));
                        i++;
                    }
                }
                case '&' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '&') {
                        out.add(new Tok(TokKind.AND, "&&", i));
                        i += 2;
                    } else {
                        throw reject(test, "bitwise & is outside the grammar; use 'and'");
                    }
                }
                case '|' -> {
                    if (i + 1 < n && test.charAt(i + 1) == '|') {
                        out.add(new Tok(TokKind.OR, "||", i));
                        i += 2;
                    } else {
                        throw reject(test, "bitwise | is outside the grammar; use 'or'");
                    }
                }
                case '\'', '"' -> {
                    StringBuilder value = new StringBuilder();
                    i++;
                    while (i < n && test.charAt(i) != ch) {
                        char c = test.charAt(i);
                        if (c == '\\' && i + 1 < n) {
                            value.append(test.charAt(i + 1));
                            i += 2;
                        } else {
                            value.append(c);
                            i++;
                        }
                    }
                    if (i >= n) {
                        throw reject(test, "unterminated string starting at index " + start);
                    }
                    i++;
                    out.add(new Tok(TokKind.STRING, value.toString(), start));
                }
                default -> {
                    if (Character.isDigit(ch) || ch == '-' && i + 1 < n
                            && Character.isDigit(test.charAt(i + 1)) && numberMayFollow(out)) {
                        i = ch == '-' ? i + 1 : i;
                        while (i < n && (Character.isDigit(test.charAt(i)) || test.charAt(i) == '.')) {
                            i++;
                        }
                        if (i < n && (test.charAt(i) == 'L' || test.charAt(i) == 'l')) {
                            i++;
                        }
                        out.add(new Tok(TokKind.NUMBER, test.substring(start, i), start));
                    } else if (Character.isJavaIdentifierStart(ch)) {
                        while (i < n && Character.isJavaIdentifierPart(test.charAt(i))) {
                            i++;
                        }
                        String word = test.substring(start, i);
                        out.add(new Tok(switch (word) {
                            case "and" -> TokKind.AND;
                            case "or" -> TokKind.OR;
                            case "not" -> TokKind.NOT;
                            case "eq" -> TokKind.EQ;
                            case "neq" -> TokKind.NEQ;
                            case "lt" -> TokKind.LT;
                            case "lte" -> TokKind.LTE;
                            case "gt" -> TokKind.GT;
                            case "gte" -> TokKind.GTE;
                            case "true" -> TokKind.TRUE;
                            case "false" -> TokKind.FALSE;
                            case "null" -> TokKind.NULL;
                            default -> TokKind.IDENT;
                        }, word, start));
                    } else {
                        throw reject(test, "unexpected character '" + ch + "' at index " + i
                                + "; <if test> accepts property paths, literals, comparisons"
                                + " (== != < <= > >=), and/or/not, and the calls size(),"
                                + " length() and isEmpty()");
                    }
                }
            }
        }
        out.add(new Tok(TokKind.EOF, "", n));
        return out;
    }

    /** A '-' starts a negative number only where an operand may begin. */
    private static boolean numberMayFollow(List<Tok> sofar) {
        if (sofar.isEmpty()) {
            return true;
        }
        return switch (sofar.get(sofar.size() - 1).kind()) {
            case IDENT, STRING, NUMBER, TRUE, FALSE, NULL, RPAREN -> false;
            default -> true;
        };
    }

    // --- AST + parser ------------------------------------------------------------

    private sealed interface Node {
    }

    private record OrNode(Node left, Node right) implements Node {
    }

    private record AndNode(Node left, Node right) implements Node {
    }

    private record NotNode(Node inner) implements Node {
    }

    /** A comparison, op one of {@code == != < <= > >=}; null op = bare operand. */
    private record RelNode(Operand left, String op, Operand right) implements Node {
    }

    private sealed interface Operand {
    }

    /** {@code a.b.c} with an optional trailing method call {@code .m(args)}. */
    private record PathOp(List<String> segments, String methodName,
                          List<Literal> methodArgs) implements Operand {
    }

    private record LitOp(Literal literal) implements Operand {
    }

    private static final class Parser {
        private final String test;
        private final List<Tok> tokens;
        private int at;

        Parser(String test, List<Tok> tokens) {
            this.test = test;
            this.tokens = tokens;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (peek() == TokKind.OR) {
                next();
                left = new OrNode(left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseNot();
            while (peek() == TokKind.AND) {
                next();
                left = new AndNode(left, parseNot());
            }
            return left;
        }

        private Node parseNot() {
            if (peek() == TokKind.NOT) {
                next();
                return new NotNode(parseNot());
            }
            return parseRel();
        }

        private Node parseRel() {
            if (peek() == TokKind.LPAREN) {
                next();
                Node inner = parseOr();
                expect(TokKind.RPAREN);
                return inner;
            }
            Operand left = parseOperand();
            String op = switch (peek()) {
                case EQ -> "==";
                case NEQ -> "!=";
                case LT -> "<";
                case LTE -> "<=";
                case GT -> ">";
                case GTE -> ">=";
                default -> null;
            };
            if (op == null) {
                return new RelNode(left, null, null);
            }
            next();
            return new RelNode(left, op, parseOperand());
        }

        private Operand parseOperand() {
            Tok tok = tokens.get(at);
            switch (tok.kind()) {
                case STRING -> {
                    next();
                    return new LitOp(new Literal(Literal.Kind.STRING, javaString(tok.text())));
                }
                case NUMBER -> {
                    next();
                    return new LitOp(new Literal(Literal.Kind.NUMBER, javaNumber(tok.text())));
                }
                case TRUE, FALSE -> {
                    next();
                    return new LitOp(new Literal(Literal.Kind.BOOLEAN, tok.text()));
                }
                case NULL -> {
                    next();
                    return new LitOp(new Literal(Literal.Kind.NULL, "null"));
                }
                case IDENT -> {
                    return parsePath();
                }
                default -> throw reject(test, "expected a value at index " + tok.pos()
                        + ", got \"" + tok.text() + "\"");
            }
        }

        private Operand parsePath() {
            List<String> segments = new ArrayList<>();
            segments.add(expect(TokKind.IDENT).text());
            while (peek() == TokKind.DOT) {
                next();
                Tok name = expect(TokKind.IDENT);
                if (peek() == TokKind.LPAREN) {
                    next();
                    List<Literal> args = new ArrayList<>();
                    if (peek() != TokKind.RPAREN) {
                        args.add(literalArg());
                        while (peek() == TokKind.COMMA) {
                            next();
                            args.add(literalArg());
                        }
                    }
                    expect(TokKind.RPAREN);
                    return new PathOp(segments, name.text(), args);
                }
                segments.add(name.text());
            }
            return new PathOp(segments, null, List.of());
        }

        private Literal literalArg() {
            Operand arg = parseOperand();
            if (arg instanceof LitOp lit && lit.literal().kind() != Literal.Kind.NULL) {
                return lit.literal();
            }
            throw reject(test, "method arguments must be string/number/boolean literals"
                    + "; compute anything richer in Java at the call site");
        }

        private TokKind peek() {
            return tokens.get(at).kind();
        }

        private void next() {
            at++;
        }

        Tok expect(TokKind kind) {
            Tok tok = tokens.get(at);
            if (tok.kind() != kind) {
                throw reject(test, "expected " + kind + " at index " + tok.pos()
                        + ", got \"" + (tok.kind() == TokKind.EOF ? "end of expression" : tok.text())
                        + "\"");
            }
            at++;
            return tok;
        }

        private String javaString(String raw) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                switch (c) {
                    case '\\' -> sb.append("\\\\");
                    case '"' -> sb.append("\\\"");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.append('"').toString();
        }

        private String javaNumber(String raw) {
            String text = raw.endsWith("L") || raw.endsWith("l")
                    ? raw.substring(0, raw.length() - 1)
                    : raw;
            if (!text.contains(".")) {
                try {
                    Integer.parseInt(text);
                } catch (NumberFormatException outOfIntRange) {
                    return text + "L";
                }
            }
            return raw.endsWith("l") ? text + "L" : raw;
        }
    }

    // --- translation ---------------------------------------------------------------

    /** Rendered Java with a precedence level: 1 = ||, 2 = &&, 3 = comparison, 4 = atom. */
    private record Rendered(String text, int prec) {

        String at(int minPrec) {
            return prec < minPrec ? "(" + text + ")" : text;
        }
    }

    /** An evaluated operand: the value expression plus its null-guard steps. */
    private record Val(String expr, ValueKind kind, String typeFqn,
                       List<String> allSteps, List<String> preSteps, Literal lit) {

        boolean isLiteral() {
            return lit != null;
        }
    }

    private static final class Translator {
        private final String test;
        private final ExprTypes types;

        Translator(String test, ExprTypes types) {
            this.test = test;
            this.types = types;
        }

        /**
         * Translates one node. {@code facts} holds the accessor expressions
         * proven non-null by the left side of an enclosing {@code and} — the
         * classic {@code x != null and x.y ...} idiom must not re-guard
         * {@code x} (generated code is a feature, design red line 8). The
         * flow direction matches Java's {@code &&} evaluation order exactly.
         */
        Rendered booleanOf(Node node, Set<String> facts) {
            if (node instanceof OrNode or) {
                return new Rendered(booleanOf(or.left(), facts).at(1) + " || "
                        + booleanOf(or.right(), facts).at(1), 1);
            }
            if (node instanceof AndNode and) {
                Rendered left = booleanOf(and.left(), facts);
                Set<String> augmented = new LinkedHashSet<>(facts);
                augmented.addAll(factsOf(and.left()));
                return new Rendered(left.at(2) + " && "
                        + booleanOf(and.right(), augmented).at(2), 2);
            }
            if (node instanceof NotNode not) {
                return new Rendered("!" + booleanOf(not.inner(), facts).at(4), 4);
            }
            RelNode rel = (RelNode) node;
            return rel.op() == null ? bare(rel.left(), facts) : comparison(rel, facts);
        }

        /** The accessors known non-null wherever {@code node} evaluated to true. */
        private Set<String> factsOf(Node node) {
            if (node instanceof AndNode and) {
                Set<String> union = new LinkedHashSet<>(factsOf(and.left()));
                union.addAll(factsOf(and.right()));
                return union;
            }
            if (node instanceof OrNode or) {
                Set<String> intersection = new LinkedHashSet<>(factsOf(or.left()));
                intersection.retainAll(factsOf(or.right()));
                return intersection;
            }
            if (!(node instanceof RelNode rel)) {
                return Set.of(); // not: a false operand proves nothing useful
            }
            if (rel.op() == null) {
                return rel.left() instanceof PathOp path
                        ? new LinkedHashSet<>(eval(path).allSteps())
                        : Set.of();
            }
            if (rel.op().equals("!=")) {
                // x != null proves x; any other != is !(==), true on null
                if (rel.right() instanceof LitOp lit && lit.literal().kind() == Literal.Kind.NULL
                        && rel.left() instanceof PathOp path) {
                    return new LinkedHashSet<>(eval(path).allSteps());
                }
                if (rel.left() instanceof LitOp lit && lit.literal().kind() == Literal.Kind.NULL
                        && rel.right() instanceof PathOp path) {
                    return new LinkedHashSet<>(eval(path).allSteps());
                }
                return Set.of();
            }
            if (isNullLiteral(rel.left()) || isNullLiteral(rel.right())) {
                return Set.of(); // x == null proves the opposite
            }
            Set<String> steps = new LinkedHashSet<>();
            if (rel.left() instanceof PathOp path) {
                steps.addAll(eval(path).allSteps());
            }
            if (rel.right() instanceof PathOp path) {
                steps.addAll(eval(path).allSteps());
            }
            return steps;
        }

        private static boolean isNullLiteral(Operand operand) {
            return operand instanceof LitOp lit && lit.literal().kind() == Literal.Kind.NULL;
        }

        // --- bare operand used as a boolean ------------------------------------

        private Rendered bare(Operand operand, Set<String> facts) {
            if (operand instanceof LitOp lit) {
                if (lit.literal().kind() == Literal.Kind.BOOLEAN) {
                    return new Rendered(lit.literal().javaText(), 4);
                }
                throw reject(test, "a bare " + lit.literal().kind().name().toLowerCase()
                        + " literal is not a boolean");
            }
            Val val = eval((PathOp) operand);
            if (val.kind() == ValueKind.PRIM_BOOLEAN) {
                return guarded(val.allSteps(), new Rendered(val.expr(), 4), facts);
            }
            if (val.kind() == ValueKind.BOX_BOOLEAN) {
                return guarded(val.preSteps(),
                        new Rendered("Boolean.TRUE.equals(" + val.expr() + ")", 4), facts);
            }
            String path = String.join(".", pathText((PathOp) operand));
            throw reject(test, "\"" + path + "\" has type " + val.typeFqn()
                    + " and is not a boolean. MyBatis truthiness was deliberately dropped"
                    + ": write " + path
                    + (isNumeric(val.kind()) ? " != 0" : " != null") + " instead");
        }

        // --- comparisons ---------------------------------------------------------

        private Rendered comparison(RelNode rel, Set<String> facts) {
            boolean leftLit = rel.left() instanceof LitOp;
            boolean rightLit = rel.right() instanceof LitOp;
            if (leftLit && rightLit) {
                throw reject(test, "comparing two literals is a constant; drop the <if>");
            }

            // null checks first: they define their own guard shape
            Literal leftAsLit = leftLit ? ((LitOp) rel.left()).literal() : null;
            Literal rightAsLit = rightLit ? ((LitOp) rel.right()).literal() : null;
            if (leftAsLit != null && leftAsLit.kind() == Literal.Kind.NULL
                    || rightAsLit != null && rightAsLit.kind() == Literal.Kind.NULL) {
                Operand pathSide = leftLit ? rel.right() : rel.left();
                if (pathSide instanceof LitOp) {
                    throw reject(test, "null belongs on one side of == / != only");
                }
                if (!rel.op().equals("==") && !rel.op().equals("!=")) {
                    throw reject(test, "null only supports == and !=");
                }
                return nullCheck((PathOp) pathSide, rel.op().equals("=="), facts);
            }

            // normalize: path on the left
            Operand left = leftLit ? rel.right() : rel.left();
            Operand right = leftLit ? rel.left() : rel.right();
            String op = leftLit ? flip(rel.op()) : rel.op();

            Val lhs = eval((PathOp) left);
            Val rhs = right instanceof LitOp lit
                    ? literalVal(lit.literal())
                    : eval((PathOp) right);

            boolean ordering = !op.equals("==") && !op.equals("!=");
            Rendered eq = ordering
                    ? orderingCompare(lhs, rhs, op, facts)
                    : equality(lhs, rhs, facts);
            if (op.equals("!=")) {
                // a != b is exactly !(a == b): null on either side makes == false
                return new Rendered("!" + eq.at(4), 4);
            }
            return eq;
        }

        /** Mirrors the operator when normalizing {@code 5 < age} to {@code age > 5}. */
        private static String flip(String op) {
            return switch (op) {
                case "<" -> ">";
                case "<=" -> ">=";
                case ">" -> "<";
                case ">=" -> "<=";
                default -> op;
            };
        }

        private Rendered nullCheck(PathOp path, boolean equalsNull, Set<String> facts) {
            Val val = eval(path);
            if (val.kind() != null && val.kind().primitive()) {
                throw reject(test, "\"" + String.join(".", pathText(path)) + "\" has primitive type "
                        + val.typeFqn() + " and can never be null; drop the check");
            }
            List<String> steps = val.allSteps();
            if (steps.isEmpty()) {
                throw reject(test, "\"" + String.join(".", pathText(path))
                        + "\" cannot be compared with null");
            }
            // null-safe navigation, translated exactly: any null step decides.
            // Known-non-null prefixes drop out; the checked value itself stays.
            List<String> remaining = new ArrayList<>();
            for (int i = 0; i < steps.size() - 1; i++) {
                if (!facts.contains(steps.get(i))) {
                    remaining.add(steps.get(i));
                }
            }
            remaining.add(steps.get(steps.size() - 1));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < remaining.size(); i++) {
                if (i > 0) {
                    sb.append(equalsNull ? " || " : " && ");
                }
                sb.append(remaining.get(i)).append(equalsNull ? " == null" : " != null");
            }
            return new Rendered(sb.toString(), remaining.size() == 1 ? 3 : equalsNull ? 1 : 2);
        }

        private Rendered orderingCompare(Val lhs, Val rhs, String op, Set<String> facts) {
            requireNumeric(lhs);
            requireNumeric(rhs);
            List<String> guards = union(lhs.allSteps(), rhs.allSteps());
            return guarded(guards, new Rendered(lhs.expr() + " " + op + " " + rhs.expr(), 3), facts);
        }

        private Rendered equality(Val lhs, Val rhs, Set<String> facts) {
            // enum vs string literal: a typed constant, checked at build time
            if (lhs.kind() == ValueKind.ENUM && rhs.isLiteral()) {
                if (rhs.lit().kind() != Literal.Kind.STRING) {
                    throw reject(test, "enum " + lhs.typeFqn() + " compares with a 'CONSTANT' literal");
                }
                String constant = unquote(rhs.lit().javaText());
                if (!types.enumHasConstant(lhs.typeFqn(), constant)) {
                    throw reject(test, lhs.typeFqn() + " has no constant " + constant);
                }
                return guarded(lhs.preSteps(),
                        new Rendered(lhs.expr() + " == " + lhs.typeFqn() + "." + constant, 3),
                        facts);
            }
            if (lhs.kind() == ValueKind.ENUM && rhs.kind() == ValueKind.ENUM) {
                return guarded(union(lhs.preSteps(), rhs.preSteps()),
                        new Rendered(lhs.expr() + " == " + rhs.expr(), 3), facts);
            }
            if (lhs.kind() == ValueKind.STRING) {
                if (rhs.isLiteral()) {
                    if (rhs.lit().kind() != Literal.Kind.STRING) {
                        throw reject(test, "String compares with a string literal, got "
                                + rhs.lit().javaText());
                    }
                    return guarded(lhs.preSteps(),
                            new Rendered(rhs.expr() + ".equals(" + lhs.expr() + ")", 4), facts);
                }
                if (rhs.kind() == ValueKind.STRING) {
                    return guarded(union(lhs.preSteps(), rhs.preSteps()),
                            new Rendered("java.util.Objects.equals(" + lhs.expr() + ", "
                                    + rhs.expr() + ")", 4), facts);
                }
                throw reject(test, "cannot compare String with " + rhs.typeFqn());
            }
            if (lhs.kind() == ValueKind.PRIM_BOOLEAN || lhs.kind() == ValueKind.BOX_BOOLEAN) {
                if (!rhs.isLiteral() || rhs.lit().kind() != Literal.Kind.BOOLEAN) {
                    throw reject(test, "boolean compares with true or false");
                }
                Rendered bare = lhs.kind() == ValueKind.PRIM_BOOLEAN
                        ? guarded(lhs.allSteps(), new Rendered(lhs.expr(), 4), facts)
                        : guarded(lhs.preSteps(),
                                new Rendered("Boolean.TRUE.equals(" + lhs.expr() + ")", 4), facts);
                return rhs.lit().javaText().equals("true")
                        ? bare
                        : new Rendered("!" + bare.at(4), 4);
            }
            requireNumeric(lhs);
            requireNumeric(rhs);
            return guarded(union(lhs.allSteps(), rhs.allSteps()),
                    new Rendered(lhs.expr() + " == " + rhs.expr(), 3), facts);
        }

        private void requireNumeric(Val val) {
            if (val.isLiteral()) {
                if (val.lit().kind() != Literal.Kind.NUMBER) {
                    throw reject(test, val.expr() + " is not a number");
                }
                return;
            }
            if (!isNumeric(val.kind())) {
                throw reject(test, "\"" + val.expr() + "\" has type " + val.typeFqn()
                        + "; <, <=, >, >= and numeric == need int/long/short/byte/float/double"
                        + " (or their boxes). BigDecimal and dates compare in Java at the call"
                        + " site");
            }
        }

        // --- operand evaluation ---------------------------------------------------

        private Val literalVal(Literal lit) {
            return new Val(lit.javaText(), null, null, List.of(), List.of(), lit);
        }

        private Val eval(PathOp path) {
            Value value = types.root(path.segments().get(0));
            for (int i = 1; i < path.segments().size(); i++) {
                value = types.property(value, path.segments().get(i));
            }
            if (path.methodName() == null) {
                return new Val(value.javaExpr(), value.kind(), value.typeFqn(),
                        value.nullableSteps(), value.stepsBeforeSelf(), null);
            }
            Call call = types.method(value, path.methodName(), path.methodArgs());
            // the receiver itself must be non-null for the call — all its steps guard
            return new Val(call.javaExpr(), call.kind(), call.kind().toString(),
                    value.nullableSteps(), value.nullableSteps(), null);
        }

        private List<String> pathText(PathOp path) {
            return path.segments();
        }

        // --- rendering helpers ------------------------------------------------------

        private Rendered guarded(List<String> steps, Rendered core, Set<String> facts) {
            List<String> needed = steps.stream().filter(s -> !facts.contains(s)).toList();
            if (needed.isEmpty()) {
                return core;
            }
            StringBuilder sb = new StringBuilder();
            for (String step : needed) {
                sb.append(step).append(" != null && ");
            }
            sb.append(core.at(2));
            return new Rendered(sb.toString(), 2);
        }

        private static List<String> union(List<String> a, List<String> b) {
            Set<String> merged = new LinkedHashSet<>(a);
            merged.addAll(b);
            return List.copyOf(merged);
        }

        private static boolean isNumeric(ValueKind kind) {
            return kind != null && switch (kind) {
                case PRIM_BYTE, PRIM_SHORT, PRIM_INT, PRIM_LONG, PRIM_FLOAT, PRIM_DOUBLE,
                     BOX_BYTE, BOX_SHORT, BOX_INT, BOX_LONG, BOX_FLOAT, BOX_DOUBLE -> true;
                default -> false;
            };
        }

        private static String unquote(String javaText) {
            return javaText.substring(1, javaText.length() - 1);
        }

        private LarkBatisProcessingException reject(String test, String message) {
            return ExprCompiler.reject(test, message);
        }
    }
}
