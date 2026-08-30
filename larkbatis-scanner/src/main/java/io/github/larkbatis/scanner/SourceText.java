package io.github.larkbatis.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A source file held as text, with comments blanked out.
 *
 * <p>Line numbers are the whole point of this tool, and neither a DOM nor a
 * StAX reader hands them out reliably for a {@code ${}} in the middle of a
 * text node. So structure comes from the parser and positions come from here:
 * a regex over lines that cannot be wrong about which line it matched on.
 *
 * <p>Comments are replaced by spaces rather than removed, so every character
 * keeps its line and column. Without that, a commented-out {@code ${}} — which
 * every legacy mapper set has — would be reported as work to do.
 */
public final class SourceText {

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern JAVA_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private final Path file;
    private final List<String> lines;

    private SourceText(Path file, List<String> lines) {
        this.file = file;
        this.lines = lines;
    }

    public static SourceText ofXml(Path file) throws IOException {
        return new SourceText(file, split(blank(read(file), XML_COMMENT)));
    }

    public static SourceText ofJava(Path file) throws IOException {
        String text = blank(read(file), JAVA_BLOCK_COMMENT);
        return new SourceText(file, split(blank(text, JAVA_LINE_COMMENT)));
    }

    public Path file() {
        return file;
    }

    public List<String> lines() {
        return lines;
    }

    public String line(int number) {
        return number >= 1 && number <= lines.size() ? lines.get(number - 1) : "";
    }

    /** Every match of {@code pattern}, paired with its 1-based line number. */
    public List<Match> matches(Pattern pattern) {
        List<Match> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            while (matcher.find()) {
                found.add(new Match(i + 1, matcher.start(), matcher.group(), groupOrNull(matcher)));
            }
        }
        return found;
    }

    private static String groupOrNull(Matcher matcher) {
        return matcher.groupCount() >= 1 ? matcher.group(1) : null;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Replaces every match with spaces and newlines, preserving positions. */
    private static String blank(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            for (int i = matcher.start(); i < matcher.end(); i++) {
                out.append(text.charAt(i) == '\n' ? '\n' : ' ');
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static List<String> split(String text) {
        return List.of(text.split("\n", -1));
    }

    /**
     * One regex hit: its 1-based line, its 0-based column, the whole match,
     * and group 1. The column matters because a rule can depend on what comes
     * before the match on its own line.
     */
    public record Match(int line, int column, String text, String group) {
    }
}
