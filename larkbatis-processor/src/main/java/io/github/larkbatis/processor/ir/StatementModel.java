package io.github.larkbatis.processor.ir;

import java.util.List;

/**
 * Everything the emitter needs to generate one mapper method body — resolved
 * entirely from the shape of the mapper. Values, and only
 * values, are supplied at runtime.
 *
 * @param methodName    mapper method name (also used in constant names:
 *                      SQL_x, KEYS_x, COLS_x, STMT_x)
 * @param statementId   unique id, {@code mapperFqn.method}, used by
 *                      trackVariants and error messages
 * @param kind          SELECT/INSERT/UPDATE/DELETE
 * @param pieces        lowered SQL in source order
 * @param params        method parameters, in order
 * @param returnShape   MANY / ONE / UPDATE_COUNT
 * @param returnTypeFqn declared return type, for the generated signature
 * @param scalarKind    non-null when the SELECT result is a scalar
 *                      (read from column 1); null for bean results
 * @param scalarEnumType enum FQN when {@code scalarKind == ENUM}
 * @param resultFqn     bean result class FQN (key into the ResultModel
 *                      registry); null for scalar results and DML
 * @param readerAccess  how a bean SELECT reaches its reader; null otherwise
 * @param keys          generated-key handling; null unless requested
 * @param batch         batch-loop info; null for single-row statements
 * @param dynamic       dynamic-SQL shape (condition locals + guarded
 *                      segments); null for static statements. When present,
 *                      {@code pieces} holds the same pieces flattened in
 *                      segment order, for the piece-scanning consumers
 *                      (select-list parsing, {@code hasDollar}, IR dumps)
 * @param nested        one-level {@code <association>}/{@code <collection>}
 *                      filled from a join; null for a flat result
 */
public record StatementModel(
        String methodName,
        String statementId,
        StatementKind kind,
        List<SqlPiece> pieces,
        List<ParamModel> params,
        ReturnShape returnShape,
        String returnTypeFqn,
        ValueKind scalarKind,
        String scalarEnumType,
        String resultFqn,
        ReaderAccess readerAccess,
        KeyModel keys,
        Batch batch,
        DynamicModel dynamic,
        NestedResult nested) {

    /**
     * A DML statement whose single parameter is a {@code List<T>}: the
     * generated body loops, binds against the loop variable, and addBatch()es.
     *
     * @param paramName      the list parameter's name
     * @param loopVar        loop variable the bind accessors are written against
     * @param elementTypeFqn element type of the list
     */
    public record Batch(String paramName, String loopVar, String elementTypeFqn) {
    }

    public boolean hasDollar() {
        return hasDollar(pieces);
    }

    private static boolean hasDollar(List<SqlPiece> pieces) {
        return pieces.stream().anyMatch(p -> p instanceof SqlPiece.Dollar
                || p instanceof SqlPiece.Foreach foreach && hasDollar(foreach.body()));
    }

    /**
     * Whether any {@code <foreach>} is present. Like {@code ${}}, a foreach
     * makes the SQL text vary at runtime — by cardinality rather than by
     * splice — so the same variant tracking applies.
     */
    public boolean hasForeach() {
        return pieces.stream().anyMatch(p -> p instanceof SqlPiece.Foreach);
    }

    /** Statements whose SQL text is not fixed at build time get a STMT_ constant. */
    public boolean tracksVariants() {
        return hasDollar() || hasForeach();
    }

    /** Binds written into the SQL text; a {@code <foreach>} body counts once per shape. */
    public int bindCount() {
        return bindCount(pieces);
    }

    private static int bindCount(List<SqlPiece> pieces) {
        int count = 0;
        for (SqlPiece piece : pieces) {
            if (piece instanceof SqlPiece.Bind) {
                count++;
            } else if (piece instanceof SqlPiece.Foreach foreach) {
                count += bindCount(foreach.body());
            }
        }
        return count;
    }
}
