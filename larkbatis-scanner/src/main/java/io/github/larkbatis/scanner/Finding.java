package io.github.larkbatis.scanner;

import java.nio.file.Path;

/**
 * One thing to do, at one place. {@code line} is 1-based; 0 means the finding
 * is about the file rather than a line in it.
 *
 * @param statement the enclosing statement id, or null outside one
 * @param detail    what was actually found, quoted from the source
 */
public record Finding(Path file, int line, Rule rule, String statement, String detail) {

    public Severity severity() {
        return rule.severity();
    }

    public String where() {
        return line > 0 ? file + ":" + line : file.toString();
    }
}
