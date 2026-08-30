package io.github.larkbatis.scanner;

/** One {@code <select>/<insert>/<update>/<delete>} and the lines it spans. */
public record StatementRange(String id, String kind, int startLine, int endLine) {

    public boolean contains(int line) {
        return line >= startLine && line <= endLine;
    }
}
