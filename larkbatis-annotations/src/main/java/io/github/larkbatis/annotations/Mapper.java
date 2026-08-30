package io.github.larkbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapper interface whose statements (or some of them) live in a
 * mapper XML file instead of statement annotations.
 *
 * <p>The XML file is found by scanning the directories passed to the
 * processor via {@code -Alarkbatis.mapperDir} (the build-tool plugins pass
 * it automatically); the file's {@code <mapper namespace="...">} must be the
 * fully-qualified name of this interface, and each statement {@code id} must
 * match a method name. Statement resolution is per method: each abstract
 * method takes its SQL from either its annotation or the XML — having both,
 * or neither, is a compile-time error.
 *
 * <p>Purely annotation-based mappers do not need this marker; it exists so
 * the processor can see XML-only mappers at all (an interface without any
 * statement annotation would otherwise never reach a processing round).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Mapper {
}
