package io.github.larkbatis.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The findings a MyBatis codebase does not show you by looking at its mappers:
 * the type of a result-class property, and the shape of a mapper interface.
 *
 * <p>Each of these stops the build on the first statement that touches it, and
 * each used to scan clean — which is the one answer this tool must never give.
 */
class MigrationScanTypeAndShapeTest {

    @TempDir
    static Path root;

    private static MigrationScan scan;

    @BeforeAll
    static void scanFixtures() throws IOException {
        copy("TypedMapper.xml", root.resolve("mappers/TypedMapper.xml"));
        // the package path matters: the scan matches a resultType to a file by
        // its package declaration plus its file name
        copy("LegacyUser.java.txt",
                root.resolve("java/com/example/legacy/LegacyUser.java"));
        copy("InheritedMapper.java.txt",
                root.resolve("java/com/example/legacy/InheritedMapper.java"));
        scan = MigrationScan.of(root);
    }

    private static void copy(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = MigrationScanTypeAndShapeTest.class
                .getResourceAsStream("/scan/" + resource)) {
            Files.write(target, in.readAllBytes());
        }
    }

    private static List<Finding> of(Rule rule) {
        return scan.findings().stream().filter(f -> f.rule() == rule).toList();
    }

    private static Set<String> detailsOf(Rule rule) {
        return of(rule).stream().map(Finding::detail).collect(Collectors.toSet());
    }

    // --- result classes ---------------------------------------------------------

    /**
     * The whole point of the second pass: {@code LegacyUser} imports nothing
     * from {@code org.apache.ibatis}, so the Java scan skips it, and only the
     * mapper's {@code resultType} says it is worth reading.
     */
    private static Set<String> flaggedProperties() {
        return detailsOf(Rule.UNSUPPORTED_PROPERTY_TYPE).stream()
                .map(detail -> detail.substring(0, detail.indexOf(" — use ")))
                .collect(Collectors.toSet());
    }

    @Test
    void readsTheResultClassTheMapperNames() {
        assertEquals(Set.of(
                "Calendar legacyCalendar",
                "UUID token",
                "Map<String, String> attributes",
                "Set<String> tags",
                "Optional<String> nickname"), flaggedProperties());
    }

    /**
     * The date and number types a legacy DTO is full of moved into the
     * whitelist, and the report has to move with them — a blocker reported
     * against a type that now compiles is the same failure as missing one,
     * pointed the other way.
     */
    @Test
    void leavesTheWidenedTypesAlone() {
        Set<String> flagged = flaggedProperties();
        for (String supported : List.of("Date regDate", "Timestamp updatedAt",
                "OffsetDateTime observedAt", "BigInteger counter", "char grade",
                "Character flag")) {
            assertFalse(flagged.contains(supported), supported + " is in the whitelist now");
        }
    }

    /** The property, not only the type: a DTO can have three Map fields. */
    @Test
    void namesTheReplacementForEachUnsupportedType() {
        assertTrue(detailsOf(Rule.UNSUPPORTED_PROPERTY_TYPE).contains(
                "Map<String, String> attributes — use a property per column"));
        assertTrue(detailsOf(Rule.UNSUPPORTED_PROPERTY_TYPE).contains(
                "Calendar legacyCalendar — use java.time.LocalDateTime or java.time.Instant"));
    }

    /** A constant is not a mapped property. */
    @Test
    void leavesStaticFieldsAlone() {
        assertTrue(flaggedProperties().stream().noneMatch(p -> p.contains("serialVersionUID")));
    }

    /** The finding carries the class, so the report can group by result class. */
    @Test
    void attributesEachFindingToItsResultClass() {
        assertEquals(Set.of("com.example.legacy.LegacyUser"),
                of(Rule.UNSUPPORTED_PROPERTY_TYPE).stream()
                        .map(Finding::statement).collect(Collectors.toSet()));
    }

    /**
     * A blocklist, not the generator's whitelist. An enum and a
     * {@code List<Bean>} are supported, and a textual scan cannot tell either
     * of them from a class it has never heard of — so it must not guess.
     */
    @Test
    void leavesSupportedAndUnknowableTypesAlone() {
        // the declarations only — the suggestions name java.time types on purpose
        String declarations = detailsOf(Rule.UNSUPPORTED_PROPERTY_TYPE).stream()
                .map(detail -> detail.substring(0, detail.indexOf(" — use ")))
                .collect(Collectors.joining("\n"));
        assertFalse(declarations.contains("Status status"), "an enum is supported");
        assertFalse(declarations.contains("List<LegacyOrder>"),
                "a <collection> target is supported");
        assertFalse(declarations.contains("BigDecimal"), "BigDecimal is in the whitelist");
        assertFalse(declarations.contains("LocalDate joinedOn"), "java.time is the whole point");
        assertFalse(declarations.contains("String name"), "String is in the whitelist");
        assertFalse(declarations.contains("long id"), "primitives are in the whitelist");
    }

    /**
     * One property is one decision. {@code token} has both a field and a
     * hand-written setter in the fixture, which is why the scan reads fields
     * only.
     */
    @Test
    void doesNotReportAPropertyTwiceForItsFieldAndItsSetter() {
        assertEquals(1, of(Rule.UNSUPPORTED_PROPERTY_TYPE).stream()
                .filter(f -> f.detail().startsWith("UUID token")).count());
    }

    // --- map results ------------------------------------------------------------

    @Test
    void reportsMapResultTypesTheSameWayAsMapParameters() {
        assertEquals(Set.of("resultType=\"map\"", "resultType=\"java.util.HashMap\""),
                detailsOf(Rule.MAP_RESULT));
    }

    // --- #{} paths --------------------------------------------------------------

    @Test
    void reportsAPathMoreThanOnePropertyDeep() {
        assertTrue(detailsOf(Rule.DEEP_PROPERTY_PATH).contains("#{order.customer.city}"));
        assertTrue(detailsOf(Rule.DEEP_PROPERTY_PATH).contains("#{q.address.city}"),
                "a @Select body carries #{} too");
    }

    /**
     * Three segments is the threshold precisely because two are ambiguous:
     * {@code #{user.name}} is one hop against a bean and one hop against
     * {@code @Param("user")}, so it is fine under either reading.
     */
    @Test
    void leavesAOneHopPathAlone() {
        assertFalse(detailsOf(Rule.DEEP_PROPERTY_PATH).contains("#{user.name}"));
        assertFalse(detailsOf(Rule.DEEP_PROPERTY_PATH).contains("#{id}"));
    }

    /**
     * A codebase that assembles SQL in Java puts {@code "#{list[" + i + "].id}"}
     * in a source file, and the dots in {@code .append(} would read as a
     * three-deep path. The statement is already blocked by the
     * {@code @SelectProvider} rule; a second, wrong reason helps nobody.
     */
    @Test
    void ignoresAPlaceholderAssembledFromJavaStringConcatenation() {
        assertTrue(detailsOf(Rule.DEEP_PROPERTY_PATH).stream()
                .noneMatch(d -> d.contains(".append(")));
        assertTrue(detailsOf(Rule.DEEP_PROPERTY_PATH).stream()
                .noneMatch(d -> d.contains("\"")));
    }

    @Test
    void reportsAnInlineTypeHandler() {
        assertEquals(Set.of("#{flags,typeHandler=com.example.legacy.FlagsHandler}"),
                detailsOf(Rule.INLINE_TYPE_HANDLER));
    }

    // --- mapper interface shape -------------------------------------------------

    @Test
    void reportsAnOverloadOnTheSecondDeclaration() {
        List<Finding> overloads = of(Rule.OVERLOADED_MAPPER_METHOD);
        assertEquals(1, overloads.size(), "the first declaration is the one that stays");
        assertEquals("find(...)", overloads.get(0).detail());
    }

    @Test
    void reportsAMapperThatExtendsAnotherInterface() {
        assertEquals(1, of(Rule.MAPPER_INHERITANCE).size());
        assertTrue(of(Rule.MAPPER_INHERITANCE).get(0).detail().contains("BaseMapper"));
    }

    /** Every rule added here is a build failure, not a style note. */
    @Test
    void allOfTheseAreBlockersExceptTheHandler() {
        for (Rule rule : List.of(Rule.MAP_RESULT, Rule.UNSUPPORTED_PROPERTY_TYPE,
                Rule.DEEP_PROPERTY_PATH, Rule.OVERLOADED_MAPPER_METHOD,
                Rule.MAPPER_INHERITANCE)) {
            assertEquals(Severity.BLOCKER, rule.severity(), rule.name());
        }
        // the mapper needs no edit; only the handler class is rewritten
        assertEquals(Severity.REVIEW, Rule.INLINE_TYPE_HANDLER.severity());
    }
}
