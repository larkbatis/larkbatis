package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for the real {@code @lombok.Setter}, in this package on purpose:
 * the hint LarkBatis prints keys on the annotation's package, and what has to
 * be reproduced here is the *model shape* — a class carrying a Lombok
 * annotation whose accessors do not exist yet — not Lombok itself. Putting the
 * real processor on this module's test classpath would only make the test
 * depend on Lombok's own ordering.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Setter {
}
