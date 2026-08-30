package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * The IR of one mapper interface: what the frontends (annotations and mapper
 * XML) produce and the emitters consume. Contains no
 * javax.lang.model types so it can be built outside javac by the build-tool
 * plugins' scanner later.
 *
 * @param packageName package of the mapper interface (the impl lands there too)
 * @param interfaceFqn fully-qualified mapper interface name
 * @param simpleName   simple mapper interface name
 * @param statements   one per annotated abstract method, in declaration order
 */
public record MapperModel(
        String packageName,
        String interfaceFqn,
        String simpleName,
        List<StatementModel> statements) {

    public String implSimpleName() {
        return simpleName + "$$Impl";
    }

    /** Stable, human-readable dump for golden tests. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("mapper ").append(interfaceFqn).append('\n');
        for (StatementModel st : statements) {
            sb.append("  ").append(st.kind()).append(' ').append(st.methodName())
                    .append(" -> ").append(st.returnShape());
            if (st.scalarKind() != null) {
                sb.append(" scalar:").append(st.scalarKind());
            }
            if (st.resultFqn() != null) {
                sb.append(" result:").append(st.resultFqn());
            }
            if (st.readerAccess() != null) {
                sb.append(" reader:").append(st.readerAccess().mode());
                if (!st.readerAccess().columnOrder().isEmpty()) {
                    sb.append(st.readerAccess().columnOrder());
                }
                if (!st.readerAccess().columnNames().isEmpty()) {
                    sb.append(st.readerAccess().columnNames());
                }
            }
            if (st.batch() != null) {
                sb.append(" batch(").append(st.batch().loopVar()).append(')');
            }
            sb.append('\n');
            if (st.dynamic() == null) {
                appendPieces(sb, st.pieces(), "    ");
            } else {
                for (DynamicModel.CondLocal local : st.dynamic().locals()) {
                    sb.append("    local ").append(local.name()).append(" = ")
                            .append(local.javaExpr()).append('\n');
                }
                for (DynamicModel.Segment segment : st.dynamic().segments()) {
                    sb.append("    seg   ")
                            .append(segment.guard() == null ? "always" : "if " + segment.guard())
                            .append('\n');
                    appendPieces(sb, segment.pieces(), "      ");
                }
            }
            if (st.nested() != null) {
                NestedResult nested = st.nested();
                sb.append("    ").append(nested.kind().name().toLowerCase(java.util.Locale.ROOT))
                        .append(' ').append(nested.property()).append(" : ").append(nested.childFqn())
                        .append(" reader:").append(nested.childAccess().mode());
                if (!nested.childAccess().columnOrder().isEmpty()) {
                    sb.append(nested.childAccess().columnOrder());
                }
                if (!nested.childAccess().columnNames().isEmpty()) {
                    sb.append(nested.childAccess().columnNames());
                }
                sb.append(" parentKey");
                for (NestedResult.KeyProperty key : nested.parentKeys()) {
                    sb.append(' ').append(key.index()).append(':').append(key.kind());
                }
                sb.append(" childKey ").append(nested.childKey().index()).append('\n');
            }
            if (st.keys() != null) {
                sb.append("    keys  ").append(st.keys().columns());
                for (KeyModel.Assignment a : st.keys().assignments()) {
                    sb.append(' ').append(a.targetAccessor()).append('.').append(a.setterName())
                            .append(':').append(a.kind());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendPieces(StringBuilder sb, List<SqlPiece> pieces, String indent) {
        for (SqlPiece piece : pieces) {
            if (piece instanceof SqlPiece.Text t) {
                sb.append(indent).append("text  |").append(t.sql()).append("|\n");
            } else if (piece instanceof SqlPiece.Bind b) {
                sb.append(indent).append("bind  ").append(b.expression()).append(" = ")
                        .append(b.accessor()).append(" : ").append(b.kind()).append('\n');
            } else if (piece instanceof SqlPiece.Dollar d) {
                sb.append(indent).append("splice ").append(d.expression()).append(" : ")
                        .append(d.dollarKind()).append('\n');
            } else if (piece instanceof SqlPiece.Alt a) {
                sb.append(indent).append("alt   ").append(a.condition())
                        .append(" ? |").append(a.whenTrue())
                        .append("| : |").append(a.whenFalse()).append("|\n");
            }
        }
    }
}
