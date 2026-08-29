package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly selects a custom type handler for one parameter or one result
 * property. There is no registry lookup and no discovery scan: the handler
 * class is referenced directly from the generated code (design §08, group 2).
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
public @interface Handler {

    /** The handler class the generated code delegates to. */
    Class<?> value();
}
