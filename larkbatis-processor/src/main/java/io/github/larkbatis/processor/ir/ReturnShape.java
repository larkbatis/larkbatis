package io.github.larkbatis.processor.ir;

/** What the generated method body hands back to the caller. */
public enum ReturnShape {
    /** {@code List<T>} of rows. */
    MANY,
    /** Zero rows → null (or a throw for primitives); one row → the value. */
    ONE,
    /**
     * A {@code Stream<T>} over the open ResultSet — the caller closes it, and
     * closing releases the statement and the Connection.
     */
    STREAM,
    /** DML update count, adapted to int/long/boolean/void. */
    UPDATE_COUNT
}
