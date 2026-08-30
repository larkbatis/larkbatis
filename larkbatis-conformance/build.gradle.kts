// Differential test harness: the same statements
// run through MyBatis (the oracle) and through LarkBatis-generated code, both
// against a RecordingDataSource — no database, char-for-char SQL comparison.
// Test-only module, never published; MyBatis must never touch a runtime
// classpath of LarkBatis itself.
//
// Oracle version: org.mybatis:mybatis 3.5.19 (latest release on Central).
// Ground-truth *reading* stays the sibling clone at ../mybatis-3
// (3.6.0-SNAPSHOT); if behavior diverges between the two, flag it.
dependencies {
    testImplementation(project(":larkbatis-annotations"))
    testImplementation(project(":larkbatis-runtime"))
    testAnnotationProcessor(project(":larkbatis-processor"))
    // The corpus sweep drives the XML frontend directly (parser + lowering +
    // grammar check). Test classpath of a never-published module — the
    // build-only red line is about application runtime classpaths.
    testImplementation(project(":larkbatis-processor"))
    testImplementation("org.mybatis:mybatis:3.5.19")
    // Nested result maps produce identical SQL, so the RecordingDataSource
    // proves nothing about them. The object graph is the thing to compare, and
    // that needs rows — both frameworks run against the same H2 database.
    testImplementation("com.h2database:h2:2.3.232")
}

// The mybatis-3 sibling clone (CLAUDE.md: reference source). The sweep test
// assume-skips when it is absent.
tasks.withType<Test>().configureEach {
    systemProperty("larkbatis.corpus.dir",
        rootDir.parentFile.parentFile.resolve("mybatis-3/src/test/resources").absolutePath)
}

// The XML fixtures live on the test classpath so MyBatis finds them as
// mapper resources; LarkBatis reads the same files at compile time.
tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add(
        "-Alarkbatis.mapperDir=" + layout.projectDirectory.dir("src/test/resources").asFile.absolutePath
    )
}

// The /diff-test contract: run only the differential suite plus the corpus
// sweep. -Pstatement narrows to one statement id (consumed by the suite).
tasks.register<Test>("diffTest") {
    description = "Differential harness: generated SQL vs the MyBatis oracle"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    // Its own result and report directories: `test` and `diffTest` run the
    // same classes, and sharing build/test-results/test makes one clobber the
    // other's XML mid-run when both are in the same invocation.
    binaryResultsDirectory = layout.buildDirectory.dir("test-results/diffTest/binary")
    reports.junitXml.outputLocation = layout.buildDirectory.dir("test-results/diffTest")
    reports.html.outputLocation = layout.buildDirectory.dir("reports/tests/diffTest")
    filter {
        includeTestsMatching("io.github.larkbatis.conformance.DifferentialTest")
        includeTestsMatching("io.github.larkbatis.conformance.DynamicDifferentialTest")
        includeTestsMatching("io.github.larkbatis.conformance.ForeachDifferentialTest")
        includeTestsMatching("io.github.larkbatis.conformance.ResultMapDifferentialTest")
        includeTestsMatching("io.github.larkbatis.conformance.XmlCorpusSweepTest")
    }
    if (project.hasProperty("statement")) {
        systemProperty("larkbatis.diff.statement", project.property("statement") as String)
    }
}
