package io.github.larkbatis.scanner;

/**
 * How much human judgement a finding needs. The report is ordered by this,
 * because the first question anyone asks about a 300-mapper codebase is not
 * "how many problems" but "how many of them do I have to think about".
 */
public enum Severity {

    /** No LarkBatis equivalent — the feature was dropped. The mapper changes. */
    BLOCKER("blocked on a dropped feature"),

    /** A rewrite with a known shape — the tool can say exactly what to write. */
    EDIT("needs a mechanical edit"),

    /** Supported, but only after someone decides how. */
    REVIEW("needs a decision"),

    /** Compiles as-is; worth knowing before it surprises someone. */
    INFO("worth knowing");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
