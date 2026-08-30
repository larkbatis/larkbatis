/**
 * The consumer side of the module story (design red line: generated code must
 * be usable from a named module). Generated mappers land in this module's own
 * package, so nothing has to be exported for them — but three directives are
 * needed, and the third is the one that surprises people:
 *
 * <ul>
 *   <li>{@code requires io.github.larkbatis.runtime} — what the generated
 *       bodies call: LarkBatisSession, JdbcCodec, RowReader.</li>
 *   <li>{@code requires static io.github.larkbatis.annotations} — every
 *       mapper annotation is CLASS-retention, so it is a compile-time-only
 *       edge.</li>
 *   <li>{@code requires static java.compiler} — every emitted source carries
 *       {@code @javax.annotation.processing.Generated}, which lives in
 *       {@code java.compiler}. Without it javac rejects the *generated*
 *       file: "package javax.annotation.processing is not visible". SOURCE
 *       retention makes it static.</li>
 * </ul>
 */
module com.example.lbsample {
    requires io.github.larkbatis.runtime;
    requires static io.github.larkbatis.annotations;
    requires static java.compiler;
    // automatic module, named from the jar manifest — re-check with
    // `jar --describe-module` after an H2 upgrade
    requires com.h2database;
    // H2's JdbcDataSource implements javax.naming.Referenceable, and an
    // automatic module cannot declare its own requires, so the consumer reads
    // java.naming for it. Nothing to do with LarkBatis.
    requires java.naming;
}
