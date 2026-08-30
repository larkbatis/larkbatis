package io.github.larkbatis.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.larkbatis.processor.frontend.LarkBatisProcessingException;
import io.github.larkbatis.processor.frontend.dyn.DynNode;
import io.github.larkbatis.processor.frontend.dyn.DynamicLowering;
import io.github.larkbatis.processor.frontend.expr.ExprCompiler;
import io.github.larkbatis.processor.frontend.xml.MapperXmlParser;
import io.github.larkbatis.processor.ir.SqlPiece;
import io.github.larkbatis.processor.ir.ValueKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The mybatis-3 XML corpus sweep: run the frontend over every mapper XML in
 * the mybatis-3 tree, print the pass rate, and blow up on none of them.
 *
 * <p>Every corpus file goes through the real frontend pieces that need no
 * interface types: {@link MapperXmlParser}, {@link DynamicLowering} with
 * permissive token/test stubs, and {@link ExprCompiler#checkGrammar} for the
 * {@code test} attributes. Outcomes are bucketed: rejections of dropped
 * features are EXPECTED and counted — the hard assertion is that no file makes
 * the frontend fail with anything but its own controlled diagnostics. The
 * typed half of the pipeline (bind resolution, result mapping) needs the
 * corpus interfaces compiled and stays out of scope here; the differential
 * suites cover it on curated fixtures.
 *
 * <p>Skipped when the sibling clone {@code ../mybatis-3} is absent. The
 * report is printed and written to {@code build/reports/larkbatis/}.
 */
class XmlCorpusSweepTest {

    private static final Path CORPUS = Path.of(System.getProperty(
            "larkbatis.corpus.dir", "../../../mybatis-3/src/test/resources"));
    private static final Path REPORT = Path.of("build/reports/larkbatis/corpus-sweep.txt");

    /** Parse-rejection buckets, matched in order against the diagnostic text. */
    private static final Map<String, Predicate<String>> PARSE_BUCKETS = orderedBuckets();

    private static Map<String, Predicate<String>> orderedBuckets() {
        Map<String, Predicate<String>> buckets = new LinkedHashMap<>();
        buckets.put("<foreach> body not static", m -> m.contains("<foreach collection="));
        // <resultMap> is kept but narrowed to one level over a join; these are
        // the shapes dropped on purpose, kept apart so the report says which
        // narrowing each corpus file actually hits
        buckets.put("<constructor> (dropped)", m -> m.contains("<constructor> was dropped"));
        buckets.put("<discriminator> (dropped)", m -> m.contains("<discriminator> was dropped"));
        buckets.put("nested select (dropped)", m -> m.contains("a nested select was dropped"));
        buckets.put("resultMap ref in nested", m -> m.contains("from a nested mapping was"));
        buckets.put("resultMap > 1 level", m -> m.contains("nesting stops at one level")
                || m.contains("more than one nested mapping"));
        buckets.put("cross-mapper resultMap", m -> m.contains("no <resultMap id="));
        buckets.put("resultMap extends", m -> m.contains("result-map inheritance"));
        buckets.put("grouping-only <id>", m -> m.contains("grouping-only <id>"));
        buckets.put("<selectKey>", m -> m.contains("<selectKey>"));
        buckets.put("<bind> (dropped)", m -> m.contains("<bind> was dropped"));
        buckets.put("cache (dropped)", m -> m.contains("cache"));
        buckets.put("parameterMap (dropped)", m -> m.contains("parameterMap"));
        buckets.put("unsupported attribute", m -> m.contains("attribute \""));
        buckets.put("unknown element", m -> m.contains("unknown element"));
        buckets.put("other", m -> true);
        return buckets;
    }

    // --- counters --------------------------------------------------------------

    private int files;
    private int notMapper;
    private int notWellFormed;
    private int parsedFiles;
    private final Map<String, Integer> parseRejections = new LinkedHashMap<>();
    private final List<String> parseRejectionSamples = new ArrayList<>();

    private int statements;
    private int loweredStatic;
    private int loweredDynamic;
    private int barePathStatements;
    private int grammarRejected;
    private int loweringRejected;
    private final List<String> statementRejectionSamples = new ArrayList<>();

    private int dollarOccurrences;
    private int dollarStatements;
    private int foreachOccurrences;
    private int foreachStatements;

    private final List<String> crashes = new ArrayList<>();

    @Test
    void sweepMybatis3Corpus() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS),
                "mybatis-3 corpus not found at " + CORPUS.toAbsolutePath().normalize()
                        + " — clone the sibling repo to run the sweep");

        List<Path> xmlFiles;
        try (Stream<Path> walk = Files.walk(CORPUS)) {
            xmlFiles = walk.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        }
        for (Path xml : xmlFiles) {
            sweepFile(xml);
        }

        String report = renderReport();
        System.out.println(report);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);

        // Guard against sweeping an empty or moved corpus and calling it green.
        assertTrue(files > 300, "expected the full corpus, saw only " + files + " XML files");
        assertTrue(crashes.isEmpty(), () -> "the frontend must reject with its own diagnostics,"
                + " never crash — uncontrolled failures on:\n" + String.join("\n", crashes));
    }

    private void sweepFile(Path xml) {
        files++;
        MapperXmlParser.XmlMapper mapper;
        try {
            mapper = MapperXmlParser.parseIfMapper(xml);
        } catch (MapperXmlParser.NotWellFormedException e) {
            notWellFormed++; // the corpus keeps intentionally-broken files for parser tests
            return;
        } catch (LarkBatisProcessingException e) {
            bucketParseRejection(xml, e.getMessage());
            return;
        } catch (RuntimeException | StackOverflowError e) {
            crashes.add(xml + ": " + e);
            return;
        }
        if (mapper == null) {
            notMapper++; // config files, DTDs' neighbors, logback configs …
            return;
        }
        parsedFiles++;
        for (MapperXmlParser.XmlStatement statement : mapper.statements().values()) {
            sweepStatement(xml, statement);
        }
    }

    private void bucketParseRejection(Path xml, String message) {
        String text = message == null ? "" : message;
        for (Map.Entry<String, Predicate<String>> bucket : PARSE_BUCKETS.entrySet()) {
            if (bucket.getValue().test(text)) {
                parseRejections.merge(bucket.getKey(), 1, Integer::sum);
                if (bucket.getKey().equals("other") && parseRejectionSamples.size() < 15) {
                    parseRejectionSamples.add(text);
                }
                return;
            }
        }
    }

    /**
     * Shape-only lowering: {@code #{}} becomes an untyped bind, {@code ${}}
     * a counted splice, and each {@code test} goes through the grammar check
     * (no types — a bare path may be a boolean property or OGNL truthiness).
     */
    private void sweepStatement(Path xml, MapperXmlParser.XmlStatement statement) {
        statements++;
        var dollarsBefore = dollarOccurrences;
        var foreachBefore = foreachOccurrences;
        var tests = new SweepTestCompiler();
        try {
            DynamicLowering.Lowered lowered =
                    DynamicLowering.lower(statement.nodes(), new SweepTokenLowerer(), tests);
            if (tests.barePath) {
                barePathStatements++;
            } else if (lowered.dynamic()) {
                loweredDynamic++;
            } else {
                loweredStatic++;
            }
        } catch (LarkBatisProcessingException e) {
            if (tests.inGrammarCheck) {
                grammarRejected++;
            } else {
                loweringRejected++;
            }
            if (statementRejectionSamples.size() < 15) {
                statementRejectionSamples.add(
                        xml.getFileName() + "#" + statement.id() + ": " + e.getMessage());
            }
        } catch (RuntimeException | StackOverflowError e) {
            crashes.add(xml + "#" + statement.id() + ": " + e);
        }
        if (dollarOccurrences > dollarsBefore) {
            dollarStatements++;
        }
        if (foreachOccurrences > foreachBefore) {
            foreachStatements++;
        }
    }

    private final class SweepTokenLowerer implements DynamicLowering.TokenLowerer {
        private final List<DynNode.Foreach> pending = new ArrayList<>();
        private int depth;

        @Override
        public SqlPiece hash(String expression) {
            return new SqlPiece.Bind(expression, expression, ValueKind.STRING, null, null);
        }

        @Override
        public SqlPiece dollar(String expression) {
            dollarOccurrences++;
            return new SqlPiece.Dollar(expression,
                    SqlPiece.Dollar.DollarKind.FRAGMENT, expression, List.of());
        }

        /** Shape only: the corpus has no interfaces to resolve element types against. */
        @Override
        public void enterForeach(DynNode.Foreach node) {
            foreachOccurrences++;
            pending.add(node);
        }

        @Override
        public DynamicLowering.ForeachPlan exitForeach() {
            DynNode.Foreach node = pending.remove(pending.size() - 1);
            String item = node.item() == null ? "item" + depth++ : node.item();
            return new DynamicLowering.ForeachPlan(node.collection(), node.collection(),
                    "java.util.List<java.lang.Object>",
                    node.collection() + ".size()", SqlPiece.Foreach.Iteration.COLLECTION,
                    item, "java.lang.Object", List.of(), node.index(), false);
        }
    }

    private static final class SweepTestCompiler implements DynamicLowering.TestCompiler {
        boolean barePath;
        boolean inGrammarCheck;

        @Override
        public String compile(String test) {
            inGrammarCheck = true;
            ExprCompiler.GrammarCheck check = ExprCompiler.checkGrammar(test);
            inGrammarCheck = false;
            barePath |= check.barePath();
            return "cond";
        }
    }

    private String renderReport() {
        StringBuilder out = new StringBuilder();
        int mapperFiles = parsedFiles + parseRejections.values().stream()
                .mapToInt(Integer::intValue).sum();
        out.append("mybatis-3 XML corpus sweep — ").append(CORPUS.toAbsolutePath().normalize())
                .append('\n');
        out.append("files scanned:        ").append(files).append('\n');
        out.append("  not a <mapper>:     ").append(notMapper).append('\n');
        out.append("  not well-formed:    ").append(notWellFormed).append('\n');
        out.append("  mapper files:       ").append(mapperFiles).append('\n');
        out.append("    parsed:           ").append(parsedFiles)
                .append(percent(parsedFiles, mapperFiles)).append('\n');
        out.append("    rejected:         ")
                .append(mapperFiles - parsedFiles).append('\n');
        parseRejections.forEach((bucket, count) -> out.append("      ")
                .append(String.format("%-28s", bucket)).append(count).append('\n'));
        out.append("statements in parsed files: ").append(statements).append('\n');
        int cleanStatements = loweredStatic + loweredDynamic;
        out.append("  lowered clean:      ").append(cleanStatements)
                .append(percent(cleanStatements, statements))
                .append(" (static ").append(loweredStatic)
                .append(", dynamic ").append(loweredDynamic).append(")\n");
        out.append("  bare test path (type decides): ").append(barePathStatements).append('\n');
        out.append("  test outside grammar:          ").append(grammarRejected).append('\n');
        out.append("  rejected by lowering:          ").append(loweringRejected).append('\n');
        out.append("${} splices:          ").append(dollarOccurrences)
                .append(" in ").append(dollarStatements).append(" statements\n");
        out.append("<foreach>:            ").append(foreachOccurrences)
                .append(" in ").append(foreachStatements).append(" statements\n");
        out.append("crashes:              ").append(crashes.size()).append('\n');
        appendSamples(out, "sample parse rejections (other):", parseRejectionSamples);
        appendSamples(out, "sample statement rejections:", statementRejectionSamples);
        appendSamples(out, "crashes:", crashes);
        return out.toString();
    }

    private static void appendSamples(StringBuilder out, String title, List<String> samples) {
        if (samples.isEmpty()) {
            return;
        }
        out.append(title).append('\n');
        samples.forEach(sample -> out.append("  ").append(sample).append('\n'));
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "" : String.format(" (%.1f%%)", 100.0 * part / whole);
    }
}
