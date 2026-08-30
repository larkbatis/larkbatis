package io.github.larkbatis.conformance;

import java.util.ArrayList;
import java.util.List;

/** What one side of the differential run did, in a diff-friendly text form. */
public final class Recording {

    public static final class Prepared {
        private final String sql;
        private final String mode;
        private final List<String> binds = new ArrayList<>();

        Prepared(String sql, String mode) {
            this.sql = sql;
            this.mode = mode;
        }

        public String sql() {
            return sql;
        }

        void bind(String call) {
            binds.add(call);
        }
    }

    private final List<Prepared> prepared = new ArrayList<>();

    Prepared prepared(String sql, String mode) {
        Prepared p = new Prepared(sql, mode);
        prepared.add(p);
        return p;
    }

    /**
     * The comparison form: the SQL, the binds and their JDBC types as one
     * string, compared char-for-char. Deliberately not normalized — normalizing
     * both sides is the fast way to 100% and the fast way to a worthless test.
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (Prepared p : prepared) {
            sb.append("prepare");
            if (!p.mode.isEmpty()) {
                sb.append('[').append(p.mode).append(']');
            }
            sb.append(": |").append(p.sql).append("|\n");
            for (String bind : p.binds) {
                sb.append("  ").append(bind).append('\n');
            }
        }
        return sb.toString();
    }
}
