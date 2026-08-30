package io.github.larkbatis.scanner;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders a scan as something a person can act on: the verdict first, the
 * catalogue second, the line numbers third, and the guidance last — so that
 * reading the first fifteen lines is enough to decide whether to keep reading.
 */
public final class MigrationReport {

    private final MigrationScan scan;
    private final Severity minimum;
    private final int perFileLimit;
    private final boolean detail;

    public MigrationReport(MigrationScan scan, Severity minimum, int perFileLimit,
            boolean detail) {
        this.scan = scan;
        this.minimum = minimum;
        this.perFileLimit = perFileLimit;
        this.detail = detail;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        header(out);
        verdict(out);
        catalogue(out);
        if (detail) {
            detail(out);
        }
        guidance(out);
        return out.toString();
    }

    private void header(StringBuilder out) {
        out.append("LarkBatis migration scan — ").append(scan.root().toAbsolutePath().normalize())
                .append("\n\n");
        out.append("Scanned\n");
        row(out, "XML files", scan.xmlFiles());
        row(out, "  mapper files", scan.mapperFiles());
        row(out, "  mybatis-config", scan.configFiles());
        row(out, "Java files using MyBatis", scan.javaFilesWithMyBatis());
        row(out, "statements", scan.statementCount());
        if (scan.unreadableFiles() > 0) {
            row(out, "unreadable (skipped)", scan.unreadableFiles());
        }
        out.append('\n');
    }

    private void verdict(StringBuilder out) {
        MigrationScan.Verdict verdict = scan.verdict();
        int total = scan.statementCount();
        out.append("Verdict — what happens to the ").append(total)
                .append(total == 1 ? " statement\n" : " statements\n");
        line(out, "compiles as-is", verdict.clean(), total);
        for (Severity severity : List.of(Severity.EDIT, Severity.REVIEW, Severity.BLOCKER)) {
            line(out, severity.label(), verdict.of(severity), total);
        }
        // A whole-file finding (line 0) IS charged to every statement in its
        // file — see MigrationScan.verdict — so only the ones attached to a
        // line outside any statement are genuinely uncounted.
        long outside = scan.findings().stream()
                .filter(f -> f.statement() == null && f.line() > 0
                        && f.severity() != Severity.INFO)
                .count();
        if (outside > 0) {
            out.append("\n").append(outside)
                    .append(" more findings sit outside a statement — result maps, the"
                            + " configuration, and Java call sites. They are in the catalogue"
                            + " below but not in the percentages above.\n");
        }
        out.append('\n');
    }

    private void catalogue(StringBuilder out) {
        Map<Severity, Map<Rule, Long>> bySeverity = new LinkedHashMap<>();
        Map<Rule, Set<Path>> filesPerRule = new EnumMap<>(Rule.class);
        for (Finding finding : scan.findings()) {
            bySeverity.computeIfAbsent(finding.severity(), s -> new TreeMap<>())
                    .merge(finding.rule(), 1L, Long::sum);
            filesPerRule.computeIfAbsent(finding.rule(), r -> new HashSet<>()).add(finding.file());
        }
        out.append("Findings\n");
        boolean any = false;
        for (Severity severity : Severity.values()) {
            Map<Rule, Long> rules = bySeverity.get(severity);
            if (rules == null || rules.isEmpty()) {
                continue;
            }
            any = true;
            out.append("  ").append(severity).append(" — ").append(severity.label()).append('\n');
            rules.entrySet().stream()
                    .sorted(Map.Entry.<Rule, Long>comparingByValue().reversed())
                    .forEach(entry -> {
                        int files = filesPerRule.get(entry.getKey()).size();
                        out.append(String.format(Locale.ROOT, "    %-40s %6d  in %4d %-6s %s%n",
                                entry.getKey().title(), entry.getValue(), files,
                                files == 1 ? "file" : "files", entry.getKey().topic()));
                    });
        }
        if (!any) {
            out.append("  nothing — every mapper in this tree compiles as it stands.\n");
        }
        out.append('\n');
        hotspots(out);
    }

    /**
     * Where the work actually is. A count on its own lies about effort: one
     * generated mapper with a thousand branches and a hundred ordinary mappers
     * with ten each produce similar totals and completely different afternoons.
     */
    private void hotspots(StringBuilder out) {
        Map<Path, Long> perFile = scan.findings().stream()
                .filter(f -> f.severity() != Severity.INFO)
                .collect(Collectors.groupingBy(Finding::file, Collectors.counting()));
        long total = perFile.values().stream().mapToLong(Long::longValue).sum();
        if (perFile.size() < 2 || total == 0) {
            return;
        }
        List<Map.Entry<Path, Long>> worst = perFile.entrySet().stream()
                .sorted(Map.Entry.<Path, Long>comparingByValue().reversed())
                .limit(5)
                .toList();
        long inWorst = worst.stream().mapToLong(Map.Entry::getValue).sum();
        out.append(String.format(Locale.ROOT,
                "Where it is concentrated — %d of %d findings (%.0f%%) are in these %d of %d files%n",
                inWorst, total, 100.0 * inWorst / total, worst.size(), perFile.size()));
        for (Map.Entry<Path, Long> entry : worst) {
            out.append(String.format(Locale.ROOT, "  %6d  %s%n", entry.getValue(),
                    relative(entry.getKey())));
        }
        out.append('\n');
    }

    private void detail(StringBuilder out) {
        List<Finding> shown = scan.findings().stream()
                .filter(f -> f.severity().compareTo(minimum) <= 0)
                .sorted(Comparator.comparing((Finding f) -> f.file().toString())
                        .thenComparingInt(Finding::line)
                        .thenComparing(f -> f.rule().name()))
                .toList();
        if (shown.isEmpty()) {
            return;
        }
        out.append("Detail — at or above ").append(minimum).append('\n');
        Map<Path, List<Finding>> byFile = shown.stream()
                .collect(Collectors.groupingBy(Finding::file, LinkedHashMap::new,
                        Collectors.toList()));
        byFile.forEach((file, findings) -> {
            out.append("  ").append(relative(file)).append('\n');
            findings.stream().limit(perFileLimit).forEach(finding -> out.append(
                    String.format(Locale.ROOT, "    %-8s %-8s %-38s %s%n",
                            finding.line() > 0 ? "L" + finding.line() : "—",
                            finding.severity(),
                            finding.rule().title(),
                            oneLine(finding.detail()))));
            int hidden = findings.size() - perFileLimit;
            if (hidden > 0) {
                // Never truncate silently: a report that hides work is worse
                // than no report, because it is believed.
                out.append("    … ").append(hidden)
                        .append(" more in this file, raise --limit to see them\n");
            }
        });
        out.append('\n');
    }

    private void guidance(StringBuilder out) {
        List<Rule> used = scan.findings().stream()
                .map(Finding::rule)
                .distinct()
                .sorted(Comparator.comparing(Rule::severity).thenComparing(Rule::title))
                .toList();
        if (used.isEmpty()) {
            return;
        }
        out.append("What to do\n");
        for (Rule rule : used) {
            out.append("  ").append(rule.title()).append("  (").append(rule.severity())
                    .append(", MIGRATION.md#").append(rule.topic()).append(")\n");
            wrap(out, rule.guidance(), "      ", 76);
        }
    }

    private String relative(Path file) {
        Path root = scan.root().toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString()
                : absolute.toString();
    }

    private static String oneLine(String detail) {
        String flattened = detail == null ? "" : detail.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 90 ? flattened : flattened.substring(0, 87) + "…";
    }

    private static void row(StringBuilder out, String label, int count) {
        out.append(String.format(Locale.ROOT, "  %-28s %6d%n", label, count));
    }

    private static void line(StringBuilder out, String label, int count, int total) {
        String percent = total == 0 ? "" : String.format(Locale.ROOT, "  %5.1f%%",
                100.0 * count / total);
        out.append(String.format(Locale.ROOT, "  %-30s %6d%s%n", label, count, percent));
    }

    private static void wrap(StringBuilder out, String text, String indent, int width) {
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > indent.length()) {
                out.append(line).append('\n');
                line = new StringBuilder(indent);
            }
            if (line.length() > indent.length()) {
                line.append(' ');
            }
            line.append(word);
        }
        out.append(line).append('\n');
    }
}
