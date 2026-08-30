// Thin JDBC layer: LarkBatisSession, ConnectionProvider, Tx, JdbcCodec, SqlFragment, LarkBatisSql,
// SQLException translation. Design constraint: zero dependencies beyond the
// JDK/JDBC, ~1,500 lines, no SPI that requires classpath scanning.

description = "LarkBatis runtime — a thin JDBC layer with zero dependencies beyond the JDK"

dependencies {
    // Test-only: the hand-written emitter spec uses the mapper
    // annotations and runs against H2. Nothing here leaks into the runtime jar.
    testImplementation(project(":larkbatis-annotations"))
    testImplementation("com.h2database:h2:2.3.232")
}
