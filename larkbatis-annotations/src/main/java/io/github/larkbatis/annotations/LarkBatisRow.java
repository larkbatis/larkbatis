package io.github.larkbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that needs a generated row reader even though it never
 * appears as a mapper {@code resultType} — typically classes used only by
 * the manual escape hatch.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LarkBatisRow {
}
