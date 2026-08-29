package io.github.lightbatis.processor.ir;

import java.util.List;

/**
 * The IR of one mapper interface (design §03): what the frontends (annotation
 * now, XML in M2) produce and the emitters consume. Contains no
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

    /** Stable, human-readable dump for golden tests (build plan §05, task 6). */
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
            }
            if (st.batch() != null) {
                sb.append(" batch(").append(st.batch().loopVar()).append(')');
            }
            sb.append('\n');
            for (SqlPiece piece : st.pieces()) {
                if (piece instanceof SqlPiece.Text t) {
                    sb.append("    text  |").append(t.sql()).append("|\n");
                } else if (piece instanceof SqlPiece.Bind b) {
                    sb.append("    bind  ").append(b.expression()).append(" = ")
                            .append(b.accessor()).append(" : ").append(b.kind()).append('\n');
                } else if (piece instanceof SqlPiece.Dollar d) {
                    sb.append("    splice ").append(d.expression()).append(" : ")
                            .append(d.dollarKind()).append('\n');
                }
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
}
