package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names a mapper method parameter for use inside {@code #{}} / {@code ${}}.
 *
 * <p>Unlike MyBatis, this is a convenience rather than a necessity: parameter
 * names are read from the AST while the annotation processor runs, so the
 * {@code -parameters} compiler flag is irrelevant (design §08, group 3).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Param {

    /** The name the SQL refers to this parameter by. */
    String value();
}
