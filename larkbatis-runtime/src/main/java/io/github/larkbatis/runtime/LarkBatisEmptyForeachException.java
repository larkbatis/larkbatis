package io.github.larkbatis.runtime;

/**
 * A {@code <foreach>} collection was empty at execution time.
 *
 * <p>This is the one deliberate divergence from {@code ForEachSqlNode}, which
 * contributes nothing at all for an empty collection — not even {@code open}
 * and {@code close} (mybatis-3 {@code ForEachSqlNode.apply}). That leaves
 * {@code ... WHERE id IN} to fail at the database with a syntax error whose
 * message names neither the mapper nor the parameter. Failing here instead
 * names both, at the call site that owns the empty list.
 *
 * <p>A mapper that genuinely wants the fragment to vanish says so, and keeps
 * MyBatis's behavior: wrap the {@code <foreach>} in
 * {@code <if test="ids != null and !ids.isEmpty()">}.
 */
public class LarkBatisEmptyForeachException extends LarkBatisException {

    public LarkBatisEmptyForeachException(String statementId, String collection) {
        super("<foreach collection=\"" + collection + "\"> is empty in statement " + statementId
                + "; wrap the loop in an <if> testing the collection if an empty one should"
                + " drop the fragment instead", statementId);
    }
}
