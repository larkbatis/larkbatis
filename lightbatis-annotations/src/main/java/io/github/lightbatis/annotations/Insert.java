package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mapper method as an INSERT statement. Multiple values are joined
 * with a single space, matching MyBatis behavior.
 *
 * <p>Generated keys are requested with {@link Options#useGeneratedKeys()}
 * (design §07).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Insert {

    /** SQL text; array elements are joined with a single space. */
    String[] value();
}
