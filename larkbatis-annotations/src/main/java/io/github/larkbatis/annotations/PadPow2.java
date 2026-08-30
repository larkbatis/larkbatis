package io.github.larkbatis.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pads the placeholder count of a {@code <foreach>} up to the next power of
 * two, repeating the last element. The SQL text of a
 * {@code <foreach>} statement changes with the number of elements, so the
 * driver's and the database's statement caches grow with every cardinality
 * ever seen; padding bounds that at log₂(n) variants instead of n. Hibernate
 * calls the same trick {@code in_clause_parameter_padding}.
 *
 * <p>On an interface it applies to every statement in the mapper; on a method
 * it applies to that one. It is opt-in because repeating the last element is
 * only invisible where duplicates do not change the result — an
 * {@code IN} list. The generator enforces that: the {@code <foreach>} body
 * must be a single {@code #{}} bind and the statement must not be an
 * {@code INSERT}, otherwise padding is a compile error rather than silently
 * duplicated rows.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PadPow2 {
}
