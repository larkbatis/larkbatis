package io.github.larkbatis.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code larkbatis-scan <path>}: point it at a MyBatis codebase, get back
 * what migrating it would cost.
 *
 * <p>It compiles nothing and resolves no dependencies, so it runs against a
 * checkout of a service nobody has built yet — which is when the question
 * "would this even work for us" actually gets asked.
 */
public final class ScannerMain {

    private static final String USAGE = """
            larkbatis-scan — what would it cost to move this codebase to LarkBatis

            usage: larkbatis-scan [options] <path>...

              --summary            counts only, no per-line detail
              --min=LEVEL          detail level: BLOCKER, EDIT, REVIEW, INFO (default REVIEW)
              --limit=N            most findings listed per file (default 40)
              --out=FILE           also write the report to FILE
              --fail-on-blocker    exit 1 when anything is blocked on a dropped feature
              -h, --help           this text

            Every finding names a heading in MIGRATION.md. Nothing is rewritten:
            the report is the deliverable, and the edits are yours to make.
            """;

    private ScannerMain() {
    }

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        if (options == null) {
            System.out.print(USAGE);
            System.exit(args.length == 0 ? 2 : 0);
            return;
        }

        boolean blocked = false;
        StringBuilder collected = new StringBuilder();
        for (Path root : options.roots) {
            if (!Files.exists(root)) {
                System.err.println("larkbatis-scan: no such path: " + root);
                System.exit(2);
            }
            MigrationScan scan = MigrationScan.of(root);
            String report = new MigrationReport(scan, options.minimum, options.limit,
                    !options.summary).render();
            collected.append(report);
            System.out.print(report);
            blocked |= scan.findings().stream().anyMatch(f -> f.severity() == Severity.BLOCKER);
        }

        if (options.out != null) {
            Path parent = options.out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(options.out, collected.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("written to " + options.out.toAbsolutePath().normalize());
        }

        if (blocked && options.failOnBlocker) {
            System.exit(1);
        }
    }

    /** @return null to print the usage text */
    private record Options(java.util.List<Path> roots, Severity minimum, int limit,
            boolean summary, Path out, boolean failOnBlocker) {

        static Options parse(String[] args) {
            java.util.List<Path> roots = new java.util.ArrayList<>();
            Severity minimum = Severity.REVIEW;
            int limit = 40;
            boolean summary = false;
            Path out = null;
            boolean failOnBlocker = false;

            for (String arg : args) {
                if (arg.equals("-h") || arg.equals("--help")) {
                    return null;
                } else if (arg.equals("--summary")) {
                    summary = true;
                } else if (arg.equals("--fail-on-blocker")) {
                    failOnBlocker = true;
                } else if (arg.startsWith("--min=")) {
                    minimum = Severity.valueOf(
                            arg.substring("--min=".length()).toUpperCase(Locale.ROOT));
                } else if (arg.startsWith("--limit=")) {
                    limit = Integer.parseInt(arg.substring("--limit=".length()));
                } else if (arg.startsWith("--out=")) {
                    out = Path.of(arg.substring("--out=".length()));
                } else if (arg.startsWith("-")) {
                    System.err.println("larkbatis-scan: unknown option " + arg);
                    return null;
                } else {
                    roots.add(Path.of(arg));
                }
            }
            return roots.isEmpty() ? null
                    : new Options(roots, minimum, limit, summary, out, failOnBlocker);
        }
    }
}
