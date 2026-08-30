/**
 * The runtime the generated mappers call into. Two JDK modules and nothing
 * else — the zero-dependencies red line holds on the module path too.
 *
 * <p>No {@code opens} and no {@code uses}: nothing here reflects into a
 * consumer's packages and there is no ServiceLoader. A descriptor that seems
 * to need {@code opens} would mean a reflection leak to find, not a directive
 * to add.
 */
module io.github.larkbatis.runtime {
    // transitive because this module's API *is* java.sql types: conn() hands
    // back a Connection, RowReader takes a ResultSet, JdbcCodec takes a
    // PreparedStatement, translate() takes a SQLException. Generated mapper
    // bodies in a consumer's module therefore name java.sql directly, and
    // making every consumer write `requires java.sql` for types our own API
    // handed them is what `transitive` exists to avoid.
    requires transitive java.sql;
    // not transitive: the Logger in LarkBatisSql never appears in a signature
    requires java.logging;

    exports io.github.larkbatis.runtime;
}
