package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapper method as a SELECT statement. Multiple values are joined
 * with a single space, matching MyBatis behavior.
 *
 * <p>The SQL is resolved entirely at build time (design §02): {@code #{}}
 * placeholders become positional {@code ?} parameters, and {@code ${}} is
 * only accepted for the parameter kinds listed in design §08.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Select {

    /** SQL text; array elements are joined with a single space. */
    String[] value();
}
