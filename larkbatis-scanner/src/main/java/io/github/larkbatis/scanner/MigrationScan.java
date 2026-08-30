package io.github.larkbatis.scanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Walks a source tree and collects everything the report needs.
 *
 * <p>The unit of the verdict is the <em>statement</em>, not the file and not
 * the finding: "893 of 1204 statements compile as they are" is the sentence
 * that decides whether a migration gets proposed, and one {@code <bind>} in a
 * 90-statement mapper should not condemn the other 89.
 */
public final class MigrationScan {

    private static final Set<String> SKIP_DIRECTORIES =
            Set.of("build", "target", "out", ".git", ".gradle", ".idea", "node_modules", "bin");

    private final Path root;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<Path, List<StatementRange>> statementsByFile = new LinkedHashMap<>();
    /** Result-class FQNs named by the mappers, and where the walk saw each Java file. */
    private final Set<String> resultClasses = new LinkedHashSet<>();
    private final Map<String, Path> javaFileByFqn = new LinkedHashMap<>();

    private int xmlFiles;
    private int mapperFiles;
    private int configFiles;
    private int javaFilesWithMyBatis;
    private int unreadable;

    public MigrationScan(Path root) {
        this.root = root;
    }

    public static MigrationScan of(Path root) throws IOException {
        MigrationScan scan = new MigrationScan(root);
        scan.walk();
        scan.scanResultClasses();
        return scan;
    }

    public Path root() {
        return root;
    }

    public List<Finding> findings() {
        return findings;
    }

    public int xmlFiles() {
        return xmlFiles;
    }

    public int mapperFiles() {
        return mapperFiles;
    }

    public int configFiles() {
        return configFiles;
    }

    public int javaFilesWithMyBatis() {
        return javaFilesWithMyBatis;
    }

    public int unreadableFiles() {
        return unreadable;
    }

    public int statementCount() {
        return statementsByFile.values().stream().mapToInt(List::size).sum();
    }

    private void walk() throws IOException {
        if (Files.isRegularFile(root)) {
            scanFile(root);
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(this::notUnderSkippedDirectory)
                    .sorted()
                    .forEach(this::scanFileQuietly);
        }
    }

    /**
     * Skips generated and VCS directories — but only <em>below</em> the root.
     * Matching against the whole path would make a scan of
     * {@code ~/work/build/service} find nothing at all and report it as a
     * clean codebase, which is the worst answer this tool could give.
     */
    private boolean notUnderSkippedDirectory(Path path) {
        for (Path part : root.relativize(path)) {
            if (SKIP_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private void scanFileQuietly(Path file) {
        try {
            scanFile(file);
        } catch (IOException | UncheckedIOException e) {
            unreadable++;
        }
    }

    private void scanFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".xml")) {
            scanXml(file);
        } else if (name.endsWith(".java")) {
            SourceText source = SourceText.ofJava(file);
            List<Finding> found = JavaSourceScan.scan(source);
            if (!found.isEmpty()) {
                javaFilesWithMyBatis++;
                findings.addAll(found);
            }
            // The path only. Holding every source text of a large codebase to
            // re-read a handful of result classes later is the wrong trade.
            javaFileByFqn.putIfAbsent(JavaSourceScan.fqnOf(source), file);
        }
    }

    /**
     * Pass two, over the classes the mappers name as results. It runs after
     * the walk because a result class is usually read before the mapper that
     * names it, and there is no way to know it is a result class until then.
     *
     * <p>A result type outside the scanned tree — a shared DTO from another
     * module, a class named through a {@code typeAlias} — is passed over in
     * silence. Saying nothing about a file this scan never saw is the honest
     * answer, and the report already declares its counts to be a floor.
     */
    private void scanResultClasses() {
        for (String fqn : resultClasses) {
            Path file = javaFileByFqn.get(fqn);
            if (file == null) {
                continue;
            }
            try {
                findings.addAll(ResultClassScan.scan(file, fqn));
            } catch (IOException | UncheckedIOException e) {
                unreadable++;
            }
        }
    }

    private void scanXml(Path file) throws IOException {
        xmlFiles++;
        MyBatisConfigScan config = MyBatisConfigScan.scan(file);
        if (config != null) {
            configFiles++;
            findings.addAll(config.findings());
            return;
        }
        XmlMapperScan mapper = XmlMapperScan.scan(file);
        if (mapper == null) {
            return; // not a mapper: logback.xml, spring context, a broken fixture
        }
        mapperFiles++;
        statementsByFile.put(file, mapper.statements());
        findings.addAll(mapper.findings());
        resultClasses.addAll(mapper.resultClasses());
    }

    // --- the verdict ------------------------------------------------------------

    /**
     * Every statement, bucketed by the worst thing found on its lines. INFO
     * findings do not move a statement out of "compiles as-is", which is the
     * whole reason INFO exists as a level.
     *
     * <p>A finding that belongs to the file rather than to any one line —
     * the frontend rejecting the whole mapper is the one that matters — is
     * charged to <em>every</em> statement in that file. It has to be: a file
     * that does not parse compiles none of its statements, and reporting
     * "3 of 3 compile as-is" underneath a BLOCKER is the single most
     * misleading thing this report could say. The person reading it is
     * deciding whether to attempt a migration.
     */
    public Verdict verdict() {
        Map<Path, List<Finding>> byLine = findings.stream()
                .filter(f -> f.line() > 0 && f.severity() != Severity.INFO)
                .collect(Collectors.groupingBy(Finding::file));
        Map<Path, List<Finding>> wholeFile = findings.stream()
                .filter(f -> f.line() <= 0 && f.severity() != Severity.INFO)
                .collect(Collectors.groupingBy(Finding::file));

        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        int clean = 0;
        for (Map.Entry<Path, List<StatementRange>> entry : statementsByFile.entrySet()) {
            List<Finding> inFile = byLine.getOrDefault(entry.getKey(), List.of());
            Severity fileWide = wholeFile.getOrDefault(entry.getKey(), List.<Finding>of()).stream()
                    .map(Finding::severity)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            for (StatementRange statement : entry.getValue()) {
                Severity worst = Stream.concat(
                                inFile.stream()
                                        .filter(f -> statement.contains(f.line()))
                                        .map(Finding::severity),
                                Stream.ofNullable(fileWide))
                        .min(Comparator.naturalOrder())
                        .orElse(null);
                if (worst == null) {
                    clean++;
                } else {
                    counts.merge(worst, 1, Integer::sum);
                }
            }
        }
        return new Verdict(clean, counts);
    }

    /**
     * @param clean  statements that compile with no edit
     * @param counts statements per worst severity; INFO is always 0
     */
    public record Verdict(int clean, Map<Severity, Integer> counts) {

        public int of(Severity severity) {
            return counts.getOrDefault(severity, 0);
        }
    }
}
