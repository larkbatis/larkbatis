package io.github.larkbatis.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.lang.model.element.Modifier;

/**
 * The {@code static final} handler instances a generated type holds, one per
 * distinct handler class, named in order of first use.
 *
 * <p>The field is declared with the handler's own class rather than with
 * {@code LarkBatisTypeHandler}: the call site is then monomorphic in the
 * bytecode, which is the whole reason a handler costs a direct call here and a
 * registry lookup plus an interface dispatch in MyBatis. It also means a
 * handler is constructed once per generated type and shared, which is the
 * contract the processor checks when it insists on a public no-argument
 * constructor.
 */
final class HandlerFields {

    private final Map<String, String> namesByFqn = new LinkedHashMap<>();

    /** The field name for a handler FQN, allocating one on first use. */
    String fieldFor(String handlerFqn) {
        return namesByFqn.computeIfAbsent(handlerFqn, fqn -> "H" + namesByFqn.size());
    }

    /** Same, tolerating a null handler so callers need no branch. */
    String fieldForOrNull(String handlerFqn) {
        return handlerFqn == null ? null : fieldFor(handlerFqn);
    }

    boolean isEmpty() {
        return namesByFqn.isEmpty();
    }

    /** Declares every allocated field on the type being built. */
    void addTo(TypeSpec.Builder type) {
        for (Map.Entry<String, String> entry : namesByFqn.entrySet()) {
            ClassName handler = ClassName.bestGuess(entry.getKey());
            type.addField(FieldSpec.builder(handler, entry.getValue(),
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T()", handler)
                    .build());
        }
    }
}
