package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapper method as a DELETE statement. Multiple values are joined
 * with a single space, matching MyBatis behavior.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Delete {

    /** SQL text; array elements are joined with a single space. */
    String[] value();
}
