package io.github.larkbatis.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three ways this scan used to say "clean" about a codebase that does not
 * compile: a file the frontend rejects outright, the {@code test} idiom every
 * legacy mapper contains, and a type-alias table.
 */
class MigrationScanVerdictAndAliasTest {

    @TempDir
    Path root;

    private MigrationScan scanOf(String mapperXml, String configXml) throws IOException {
        Path mapper = root.resolve("mappers/PMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PMapper">
                %s
                </mapper>
                """.formatted(mapperXml));
        if (configXml != null) {
            Files.writeString(root.resolve("mybatis-config.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-config.dtd">
                    <configuration>
                    %s
                    </configuration>
                    """.formatted(configXml));
        }
        return MigrationScan.of(root);
    }

    private static Set<Rule> rulesOf(MigrationScan scan) {
        return scan.findings().stream().map(Finding::rule).collect(Collectors.toSet());
    }

    private static long count(MigrationScan scan, Rule rule) {
        return scan.findings().stream().filter(f -> f.rule() == rule).count();
    }

    /**
     * A file the frontend refuses compiles none of its statements, so none of
     * them may be counted as compiling. The finding has no line of its own —
     * it is about the file — and being uncounted was exactly how a BLOCKER
     * came to sit underneath "3 of 3 compile as-is".
     */
    @Test
    void aRejectedFileCondemnsEveryStatementInIt() throws IOException {
        MigrationScan scan = scanOf("""
                  <select id="a" resultType="com.example.User">SELECT id FROM t</select>
                  <select id="b" resultType="com.example.User" databaseId="mysql">
                    SELECT id FROM t WHERE id = #{id}
                  </select>
                  <select id="c" resultType="com.example.User">SELECT id FROM t WHERE id = #{id}</select>
                """, null);

        assertTrue(rulesOf(scan).contains(Rule.PARSE_REJECTED), "the file must be rejected");
        MigrationScan.Verdict verdict = scan.verdict();
        assertEquals(3, scan.statementCount());
        assertEquals(0, verdict.clean(), "nothing in an unparseable file compiles as-is");
        assertEquals(3, verdict.of(Severity.BLOCKER));
        String report = new MigrationReport(scan, Severity.REVIEW, 10, true).render();
        assertFalse(report.contains("more findings sit outside a statement"),
                "the file-wide finding is counted now, so it is not also called uncounted");
    }

    /**
     * {@code name != null and name.trim() != ''} is the most common
     * {@code <if test>} in MyBatis and the processor refuses it: the grammar
     * takes a condition, not a value. The syntax-only grammar check the
     * scanner runs cannot see that on its own.
     */
    @Test
    void aValueCallInATestIsReportedAndABooleanGetterIsNot() throws IOException {
        MigrationScan scan = scanOf("""
                  <select id="a" resultType="com.example.User">
                    SELECT id FROM t
                    <where>
                      <if test="name != null and name.trim() != ''">name = #{name}</if>
                      <if test="q.isActive()">active = 1</if>
                      <if test="ids != null and ids.size() > 0">x = 1</if>
                    </where>
                  </select>
                """, null);

        assertEquals(1, count(scan, Rule.EXPRESSION_VALUE_CALL), "trim(), and only trim()");
        assertEquals(0, count(scan, Rule.EXPRESSION_UNTYPED_CALL),
                "isActive() reads as a boolean getter and size() is always accepted");
        assertTrue(scan.findings().stream()
                        .anyMatch(f -> f.rule() == Rule.EXPRESSION_VALUE_CALL
                                && f.detail().contains("trim()")),
                "the report names the call, not just the expression");
        assertEquals(0, scan.verdict().clean(), "the one statement needs an edit");
    }

    /** A call whose return type decides its fate needs a person, not a guess. */
    @Test
    void aCallOfTheProjectsOwnNeedsADecision() throws IOException {
        MigrationScan scan = scanOf("""
                  <select id="a" resultType="com.example.User">
                    SELECT id FROM t
                    <where><if test="q.matchesFilter()">x = 1</if></where>
                  </select>
                """, null);

        assertEquals(1, count(scan, Rule.EXPRESSION_UNTYPED_CALL));
        assertEquals(1, scan.verdict().of(Severity.REVIEW));
    }

    /**
     * The cost an alias table hides: every type name in the mappers is an
     * edit, and a {@code <package>} scan is why. Counting them is possible
     * without resolving them, and resolving them is what would put a wrong
     * class name in someone else's migration report.
     */
    @Test
    void aliasesAreCountedWithoutBeingResolved() throws IOException {
        MigrationScan scan = scanOf("""
                  <select id="a" parameterType="Criteria" resultType="usr">
                    SELECT id FROM t WHERE id = #{id}
                  </select>
                  <select id="b" resultType="long">SELECT count(*) FROM t</select>
                  <select id="c" resultType="com.example.User">SELECT id FROM t</select>
                """, """
                  <typeAliases>
                    <package name="com.example.model"/>
                    <typeAlias alias="usr" type="com.example.model.User"/>
                  </typeAliases>
                  <mappers><package name="com.example.mapper"/></mappers>
                """);

        assertEquals(2, count(scan, Rule.TYPE_ALIAS_DECLARED),
                "the <package> under <mappers> declares no alias");
        assertEquals(2, count(scan, Rule.UNQUALIFIED_TYPE_NAME),
                "resultType=\"usr\" and parameterType=\"Criteria\"");
        assertTrue(scan.findings().stream()
                        .noneMatch(f -> f.rule() == Rule.UNQUALIFIED_TYPE_NAME
                                && f.detail().contains("long")),
                "resultType=\"long\" is a built-in alias, not a migration cost");

        MigrationScan.Verdict verdict = scan.verdict();
        assertEquals(2, verdict.clean(), "only the aliased statement needs the edit");
        assertEquals(1, verdict.of(Severity.EDIT));
    }

    /** The scanner declines to name the class, and the report shows the name as written. */
    @Test
    void anUnqualifiedNameIsQuotedNeverGuessed() throws IOException {
        MigrationScan scan = scanOf("""
                  <select id="a" resultType="usr">SELECT id FROM t</select>
                """, null);

        List<Finding> aliasFindings = scan.findings().stream()
                .filter(f -> f.rule() == Rule.UNQUALIFIED_TYPE_NAME)
                .toList();
        assertEquals(1, aliasFindings.size());
        assertEquals("resultType=\"usr\"", aliasFindings.get(0).detail());
    }
}
