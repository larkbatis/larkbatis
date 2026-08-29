package io.github.lightbatis.processor.ir;

import java.util.List;

/**
 * Everything the emitter needs to generate one mapper method body — resolved
 * entirely from the shape of the mapper (design §02). Values, and only
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
        Batch batch) {

    /**
     * A DML statement whose single parameter is a {@code List<T>}: the
     * generated body loops, binds against the loop variable, and addBatch()es
     * (design §07 case 2).
     *
     * @param paramName      the list parameter's name
     * @param loopVar        loop variable the bind accessors are written against
     * @param elementTypeFqn element type of the list
     */
    public record Batch(String paramName, String loopVar, String elementTypeFqn) {
    }

    public boolean hasDollar() {
        return pieces.stream().anyMatch(p -> p instanceof SqlPiece.Dollar);
    }

    public int bindCount() {
        return (int) pieces.stream().filter(p -> p instanceof SqlPiece.Bind).count();
    }
}
