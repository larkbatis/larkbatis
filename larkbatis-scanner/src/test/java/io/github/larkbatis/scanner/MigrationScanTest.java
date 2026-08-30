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
 * The scanner over a fixture tree that contains one of everything: a mapper
 * XML, a mybatis-config, and an annotated Java mapper.
 *
 * <p>The fixtures are copied into a temporary directory rather than scanned in
 * place, because the walk is part of what is being tested — including the
 * rule that {@code build/} is skipped only <em>below</em> the root.
 */
class MigrationScanTest {

    @TempDir
    static Path root;

    private static MigrationScan scan;

    @BeforeAll
    static void scanFixtures() throws IOException {
        copy("LegacyMapper.xml", root.resolve("mappers/LegacyMapper.xml"));
        copy("mybatis-config.xml", root.resolve("mybatis-config.xml"));
        copy("LegacyJavaMapper.java.txt", root.resolve("java/LegacyJavaMapper.java"));
        // must be ignored: generated output, not source
        copy("LegacyMapper.xml", root.resolve("build/generated/LegacyMapper.xml"));
        scan = MigrationScan.of(root);
    }

    private static void copy(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = MigrationScanTest.class.getResourceAsStream("/scan/" + resource)) {
            Files.write(target, in.readAllBytes());
        }
    }

    @Test
    void countsOnlySourceFiles() {
        assertEquals(2, scan.xmlFiles(), "the copy under build/ must not be scanned");
        assertEquals(1, scan.mapperFiles());
        assertEquals(1, scan.configFiles());
        assertEquals(1, scan.javaFilesWithMyBatis());
        assertEquals(5, scan.statementCount());
    }

    @Test
    void bucketsEveryStatementByItsWorstFinding() {
        MigrationScan.Verdict verdict = scan.verdict();
        assertEquals(1, verdict.clean(), "only `clean` has nothing on it");
        assertEquals(1, verdict.of(Severity.BLOCKER), "`byMap` takes a Map");
        assertEquals(3, verdict.of(Severity.EDIT), "`ordered`, `truthy`, `spliced`");
        assertEquals(5, verdict.clean() + verdict.of(Severity.BLOCKER)
                + verdict.of(Severity.EDIT) + verdict.of(Severity.REVIEW));
    }

    @Test
    void reportsDollarSpliceWithItsLineAndStatement() {
        Finding splice = findings(Rule.DOLLAR_SPLICE).stream()
                .filter(f -> "ordered".equals(f.statement()))
                .findFirst()
                .orElseThrow();
        assertEquals(13, splice.line());
        assertEquals("${sort}", splice.detail());
    }

    @Test
    void marksOnlySelectListSplicesAsReaderDowngrades() {
        Set<String> statements = findings(Rule.DOLLAR_IN_SELECT_LIST).stream()
                .map(Finding::statement)
                .collect(Collectors.toSet());
        assertEquals(Set.of("spliced"), statements,
                "ORDER BY ${sort} is not in the select list; SELECT ${columns} is");
    }

    @Test
    void neverReportsCommentedOutCode() {
        assertTrue(findings(Rule.DOLLAR_SPLICE).stream().noneMatch(f -> f.line() == 32),
                "the ${splice} on line 32 is inside an XML comment");
        assertEquals(1, findings(Rule.PROVIDER_ANNOTATION).size(),
                "the @SelectProvider in the Java comment must not count, nor the import");
    }

    @Test
    void refusesOgnlTruthiness() {
        List<Finding> bare = findings(Rule.EXPRESSION_BARE_PATH);
        assertEquals(1, bare.size());
        assertEquals(19, bare.get(0).line());
        assertTrue(bare.get(0).detail().contains("test=\"name\""));
    }

    @Test
    void findsLazyFetchOnANestedMapping() {
        List<Finding> lazy = findings(Rule.LAZY_LOADING);
        assertEquals(2, lazy.size(),
                "one on <collection fetchType=\"lazy\">, one in mybatis-config");
        assertTrue(lazy.stream().anyMatch(f -> f.detail().startsWith("<collection")),
                "fetchType lives on the nested mapping, never on the statement");
    }

    @Test
    void readsTheMyBatisConfiguration() {
        assertEquals(1, findings(Rule.PLUGIN).size());
        assertTrue(findings(Rule.UNDERSCORE_MAPPING_OFF).isEmpty(),
                "the fixture sets mapUnderscoreToCamelCase=true");
    }

    @Test
    void readsTheJavaSide() {
        assertEquals(1, findings(Rule.ROW_BOUNDS).size(), "the import must not count as a use");
        assertEquals(1, findings(Rule.PROVIDER_ANNOTATION).size());
        assertTrue(findings(Rule.MAP_PARAMETER).stream()
                        .anyMatch(f -> f.file().toString().endsWith(".java")),
                "Map<String, Object> in a mapper signature");
    }

    @Test
    void rendersAReportThatNamesTheWork() {
        String report = new MigrationReport(scan, Severity.REVIEW, 40, true).render();
        assertTrue(report.contains("Verdict"), report);
        assertTrue(report.contains("${} splice"), report);
        assertTrue(report.contains("MIGRATION.md#raw-sql"), report);
        assertTrue(report.contains("mappers/LegacyMapper.xml"),
                "paths are relative to the scanned root");
        assertFalse(report.contains("§"), "the report points at MIGRATION.md, not section numbers");
    }

    private List<Finding> findings(Rule rule) {
        return scan.findings().stream().filter(f -> f.rule() == rule).toList();
    }
}
