// Thin JDBC layer: LightBatisSession, ConnectionProvider, Tx, JdbcCodec, SqlFragment, LightBatisSql,
// SQLException translation. Design constraint (§03): zero dependencies beyond the
// JDK/JDBC, ~1,500 lines, no SPI that requires classpath scanning.
dependencies {
    // Test-only: the hand-written emitter spec (M1 task 1) uses the mapper
    // annotations and runs against H2. Nothing here leaks into the runtime jar.
    testImplementation(project(":lightbatis-annotations"))
    testImplementation("com.h2database:h2:2.3.232")
}
