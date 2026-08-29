// Differential test harness (build plan §03, M0 task 5): the same statements
// run through MyBatis (the oracle) and through LightBatis-generated code, both
// against a RecordingDataSource — no database, char-for-char SQL comparison.
// Test-only module, never published; MyBatis must never touch a runtime
// classpath of LightBatis itself.
//
// Oracle version: org.mybatis:mybatis 3.5.19 (latest release on Central).
// Ground-truth *reading* stays the sibling clone at ../mybatis-3
// (3.6.0-SNAPSHOT); if behavior diverges between the two, flag it.
dependencies {
    testImplementation(project(":lightbatis-annotations"))
    testImplementation(project(":lightbatis-runtime"))
    testAnnotationProcessor(project(":lightbatis-processor"))
    testImplementation("org.mybatis:mybatis:3.5.19")
}

// The /diff-test contract: run only the differential suite. -Pstatement narrows
// to one statement id (consumed by the suite; corpus-wide filtering lands with
// the M2 XML corpus).
tasks.register<Test>("diffTest") {
    description = "Differential harness: generated SQL vs the MyBatis oracle"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("io.github.lightbatis.conformance.DifferentialTest") }
    if (project.hasProperty("statement")) {
        systemProperty("lightbatis.diff.statement", project.property("statement") as String)
    }
}
