package io.github.larkbatis.processor.frontend.expr;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expression grammar, compiled against a fake type table that mimics a single
 * unannotated bean parameter {@code UserQuery q} — the same step conventions
 * MethodExprTypes produces (the implicit receiver is never guarded, every
 * explicit reference step is).
 */
class ExprCompilerTest {

    /** Property table of the fake bean: name → (accessor, kind, typeFqn). */
    private static final Map<String, ExprTypes.Value> PROPERTIES = Map.of(
            "name", value("q.getName()", "java.lang.String", ValueKind.STRING),
            "minAge", value("q.getMinAge()", "java.lang.Integer", ValueKind.BOX_INT),
            "age", new ExprTypes.Value("q.getAge()", "int", ValueKind.PRIM_INT, List.of()),
            "flag", new ExprTypes.Value("q.isFlag()", "boolean", ValueKind.PRIM_BOOLEAN, List.of()),
            "enabled", value("q.getEnabled()", "java.lang.Boolean", ValueKind.BOX_BOOLEAN),
            "status", value("q.getStatus()", "com.example.app.Status", ValueKind.ENUM),
            "tags", value("q.getTags()", "java.util.List<java.lang.String>", null),
            "address", value("q.getAddress()", "com.example.app.Address", null));

    private static ExprTypes.Value value(String expr, String fqn, ValueKind kind) {
        return new ExprTypes.Value(expr, fqn, kind, List.of(expr));
    }

    private static final ExprTypes TYPES = new ExprTypes() {
        @Override
        public Value root(String name) {
            Value property = PROPERTIES.get(name);
            if (property == null) {
                throw new LarkBatisProcessingException(null, "\"" + name + "\" does not match");
            }
            return property;
        }

        @Override
        public Value property(Value receiver, String name) {
            if (receiver.typeFqn().equals("com.example.app.Address") && name.equals("city")) {
                String expr = receiver.javaExpr() + ".getCity()";
                List<String> steps = new java.util.ArrayList<>(receiver.nullableSteps());
                steps.add(expr);
                return new Value(expr, "java.lang.String", ValueKind.STRING, List.copyOf(steps));
            }
            throw new LarkBatisProcessingException(null, "no property " + name);
        }

        @Override
        public Call method(Value receiver, String name, List<Literal> args) {
            String call = receiver.javaExpr() + "." + name + "("
                    + String.join(", ", args.stream().map(Literal::javaText).toList()) + ")";
            return switch (name) {
                case "size", "length" -> new Call(call, ValueKind.PRIM_INT);
                case "isEmpty", "contains", "startsWith" -> new Call(call, ValueKind.PRIM_BOOLEAN);
                default -> throw new LarkBatisProcessingException(null, "no method " + name);
            };
        }

        @Override
        public boolean enumHasConstant(String enumFqn, String constantName) {
            return constantName.equals("ACTIVE") || constantName.equals("SUSPENDED");
        }
    };

    private static String compile(String test) {
        return ExprCompiler.compileBoolean(test, TYPES);
    }

    private static String rejectMessage(String test) {
        return assertThrows(LarkBatisProcessingException.class, () -> compile(test)).getMessage();
    }

    // --- the accepted grammar -------------------------------------------------

    @Test
    void nullChecks() {
        assertEquals("q.getName() != null", compile("name != null"));
        assertEquals("q.getName() == null", compile("name == null"));
        assertEquals("q.getName() != null", compile("null != name"));
    }

    @Test
    void nullCheckOnNavigatedPathPropagatesLikeOgnl() {
        assertEquals("q.getAddress() != null && q.getAddress().getCity() != null",
                compile("address.city != null"));
        assertEquals("q.getAddress() == null || q.getAddress().getCity() == null",
                compile("address.city == null"));
    }

    @Test
    void numericComparisons() {
        assertEquals("q.getAge() >= 18", compile("age >= 18"));
        assertEquals("q.getMinAge() != null && q.getMinAge() >= 18", compile("minAge >= 18"));
        assertEquals("q.getAge() > 5", compile("5 < age"));
        assertEquals("q.getAge() >= 18", compile("age gte 18"));
    }

    @Test
    void classicNullGuardIdiomIsNotDoubleGuarded() {
        assertEquals("q.getMinAge() != null && q.getMinAge() >= 18",
                compile("minAge != null and minAge >= 18"));
        assertEquals("q.getTags() != null && !q.getTags().isEmpty()",
                compile("tags != null and !tags.isEmpty()"));
    }

    @Test
    void stringEquality() {
        assertEquals("\"ACTIVE\".equals(q.getName())", compile("name == 'ACTIVE'"));
        assertEquals("!\"ACTIVE\".equals(q.getName())", compile("name != 'ACTIVE'"));
    }

    @Test
    void enumEqualityBecomesTypedConstant() {
        assertEquals("q.getStatus() == com.example.app.Status.ACTIVE",
                compile("status == 'ACTIVE'"));
        assertTrue(rejectMessage("status == 'NOPE'").contains("no constant NOPE"));
    }

    @Test
    void booleans() {
        assertEquals("q.isFlag()", compile("flag"));
        assertEquals("!q.isFlag()", compile("!flag"));
        assertEquals("Boolean.TRUE.equals(q.getEnabled())", compile("enabled"));
        assertEquals("q.isFlag()", compile("flag == true"));
        assertEquals("!q.isFlag()", compile("flag == false"));
    }

    @Test
    void methodsGuardTheirReceiver() {
        assertEquals("q.getTags() != null && q.getTags().size() > 0", compile("tags.size() > 0"));
        assertEquals("q.getName() != null && q.getName().contains(\"x\")",
                compile("name.contains('x')"));
        assertEquals("q.getName() != null && q.getName().length() > 2", compile("name.length() > 2"));
    }

    @Test
    void logicalOperatorsAndParentheses() {
        assertEquals("q.getName() != null || q.getMinAge() != null",
                compile("name != null or minAge != null"));
        assertEquals("q.isFlag() && (q.getName() != null || q.getMinAge() != null)",
                compile("flag and (name != null or minAge != null)"));
        assertEquals("!(q.getName() != null && q.isFlag())", compile("not (name != null and flag)"));
    }

    @Test
    void numericEqualityAndItsNegation() {
        assertEquals("q.getMinAge() != null && q.getMinAge() == 0", compile("minAge == 0"));
        // != is exactly !(==): a null minAge makes != true, matching OGNL for != null cases
        assertEquals("!(q.getMinAge() != null && q.getMinAge() == 0)", compile("minAge != 0"));
    }

    // --- the deliberate rejections ---------------------------------------------

    @Test
    void numberUsedAsBooleanIsRejectedWithTheFix() {
        String message = rejectMessage("age");
        assertTrue(message.contains("truthiness was deliberately dropped"), message);
        assertTrue(message.contains("age != 0"), message);
    }

    @Test
    void objectUsedAsBooleanIsRejectedWithTheFix() {
        String message = rejectMessage("address");
        assertTrue(message.contains("address != null"), message);
    }

    @Test
    void outsideTheGrammarIsRejected() {
        assertTrue(rejectMessage("@java.lang.Math@max(a, b)").contains("unexpected character"));
        assertTrue(rejectMessage("name = 'x'").contains("assignment"));
        assertTrue(rejectMessage("age == age == age").contains("expected EOF"));
        assertTrue(rejectMessage("age > 'x'").contains("is not a number"));
        assertTrue(rejectMessage("age != null").contains("can never be null"));
        assertTrue(rejectMessage("name > 'a'").contains("need int/long"));
    }

    @Test
    void methodArgumentsMustBeLiterals() {
        assertTrue(rejectMessage("name.contains(name)").contains("literal"));
    }

    // --- checkGrammar: the untyped entry (corpus sweep, scanner) ----------------

    @Test
    void checkGrammarAcceptsWithoutTypes() {
        assertFalse(ExprCompiler.checkGrammar("name != null and age > 10").barePath());
        assertFalse(ExprCompiler.checkGrammar("no.such.property == 'X'").barePath());
        assertFalse(ExprCompiler.checkGrammar("tags.isEmpty()").barePath());
    }

    @Test
    void checkGrammarFlagsBarePaths() {
        assertTrue(ExprCompiler.checkGrammar("count").barePath());
        assertTrue(ExprCompiler.checkGrammar("flag and count").barePath());
        assertTrue(ExprCompiler.checkGrammar("!deleted").barePath());
    }

    @Test
    void checkGrammarRejectsSyntaxOutsideTheGrammar() {
        assertThrows(LarkBatisProcessingException.class,
                () -> ExprCompiler.checkGrammar("@java.lang.Math@max(a, b)"));
        assertThrows(LarkBatisProcessingException.class,
                () -> ExprCompiler.checkGrammar("name = 'x'"));
        assertThrows(LarkBatisProcessingException.class,
                () -> ExprCompiler.checkGrammar("age == age == age"));
    }
}
