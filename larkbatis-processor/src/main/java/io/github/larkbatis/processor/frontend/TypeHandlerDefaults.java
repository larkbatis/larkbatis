package io.github.larkbatis.processor.frontend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A default handler per Java type, read from
 * {@code -Alarkbatis.typeHandlers} — the build-time answer to a
 * {@code mybatis-config.xml} {@code <typeHandlers>} block.
 *
 * <pre>{@code
 * -Alarkbatis.typeHandlers=com.example.Money:com.example.MoneyHandler,\
 *                          com.example.Json:com.example.JsonHandler
 * }</pre>
 *
 * <p>An entry applies to every property and every {@code #{}} of that type
 * that does not name a handler of its own; {@code @Handler} and a
 * {@code typeHandler} attribute both still win, because naming it at the site
 * is the more specific of the two.
 *
 * <p>What this is not: MyBatis resolves a handler at runtime from a
 * {@code (javaType, jdbcType)} registry it filled by scanning packages and
 * reading {@code @MappedTypes}. Nothing is scanned here and nothing is
 * resolved at runtime — the list is written out, each entry is checked during
 * {@code javac}, and the handler that wins is compiled into the reader as a
 * direct call on a field of the handler's own class. A {@code jdbcType}
 * qualifier has no meaning in that model: the generated reader knows the one
 * column it is reading.
 *
 * <p>Separated by commas, and each entry by a colon. Neither can occur in a
 * fully-qualified Java name, so no escaping is needed and a malformed entry is
 * always detectable rather than silently mis-split.
 */
public final class TypeHandlerDefaults {

    public static final TypeHandlerDefaults NONE = new TypeHandlerDefaults(Map.of(), List.of());

    private final Map<String, String> handlerByJavaType;
    private final List<String> syntaxProblems;
    /** Entries that actually moved a value, so the rest can be reported as dead. */
    private final Set<String> used = new LinkedHashSet<>();

    private TypeHandlerDefaults(Map<String, String> handlerByJavaType,
            List<String> syntaxProblems) {
        this.handlerByJavaType = handlerByJavaType;
        this.syntaxProblems = syntaxProblems;
    }

    /**
     * Parses the option. Only the shape is checked here; whether the classes
     * exist and fit each other is decided against the compilation's own type
     * model, which this class has no access to.
     */
    public static TypeHandlerDefaults parse(String option) {
        if (option == null || option.isBlank()) {
            return NONE;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String entry : option.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue; // a trailing comma, not a request to handle nothing
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                problems.add("larkbatis.typeHandlers entry \"" + trimmed
                        + "\" is not <javaType>:<handlerClass>");
                continue;
            }
            String javaType = trimmed.substring(0, colon).trim();
            String handler = trimmed.substring(colon + 1).trim();
            String previous = entries.putIfAbsent(javaType, handler);
            if (previous != null && !previous.equals(handler)) {
                problems.add("larkbatis.typeHandlers names two handlers for " + javaType + ": "
                        + previous + " and " + handler + " — one type, one handler");
            }
        }
        return new TypeHandlerDefaults(Map.copyOf(entries), List.copyOf(problems));
    }

    public boolean isEmpty() {
        return handlerByJavaType.isEmpty();
    }

    /** Entries as written, in declaration order. */
    public Map<String, String> entries() {
        return handlerByJavaType;
    }

    /** Malformed entries, one message each. */
    public List<String> syntaxProblems() {
        return syntaxProblems;
    }

    /**
     * The handler for a value of this type, or null. Marks the entry used, so
     * a type nothing in the build ever has can be reported — a typo in the
     * java-type half is otherwise completely silent.
     */
    public String handlerFor(String javaTypeFqn) {
        String handler = handlerByJavaType.get(javaTypeFqn);
        if (handler != null) {
            used.add(javaTypeFqn);
        }
        return handler;
    }

    /** Whether a type is registered, without marking the entry used. */
    public boolean covers(String javaTypeFqn) {
        return handlerByJavaType.containsKey(javaTypeFqn);
    }

    /** Registered types that moved nothing in this compilation. */
    public List<String> unusedJavaTypes() {
        List<String> unused = new ArrayList<>();
        for (String javaType : handlerByJavaType.keySet()) {
            if (!used.contains(javaType)) {
                unused.add(javaType);
            }
        }
        return unused;
    }
}
