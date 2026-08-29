package io.github.lightbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows a {@code String} parameter to be used inside {@code ${}} by closing
 * its value set at build time (design §08). The generator emits a
 * {@code switch} over the allowed literals, so the number of SQL variants is
 * bounded and no runtime check is needed on the happy path.
 *
 * <p>Prefer this over {@code SqlFragment} when the whole value set is known
 * at build time.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface OrderBy {

    /** The complete set of accepted values; anything else is rejected. */
    String[] allowed();
}
