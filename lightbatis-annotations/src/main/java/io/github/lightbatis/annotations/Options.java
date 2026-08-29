package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-statement options for the annotation path. Only the generated-keys
 * subset of the MyBatis {@code @Options} annotation is supported (design §07).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Options {

    /** Ask the driver for generated keys after an INSERT. */
    boolean useGeneratedKeys() default false;

    /**
     * Property (or {@code param.property}) the generated key is assigned to.
     * With multiple parameters the parameter name is mandatory; a wrong name
     * is a compile-time error, not a runtime exception (design §07).
     */
    String keyProperty() default "";

    /**
     * Column name(s) of the generated key, comma-separated for composite keys.
     * Strongly recommended: without explicit key columns, Oracle returns ROWID
     * and PostgreSQL returns all columns (design §07). The generator warns at
     * build time when this is missing.
     */
    String keyColumn() default "";
}
