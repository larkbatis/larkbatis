package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the column name a result-class property maps to, when the
 * {@code mapUnderscoreToCamelCase} convention applied at build time
 * (design §08, group 3) is not enough.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Column {

    /** The column name (or label) in the result set. */
    String value();
}
