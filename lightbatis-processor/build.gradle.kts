// Frontend (XML/annotations) -> MapperModel (IR) -> emitters.
// Build-only: must NEVER appear on the application's runtime classpath (§03).
dependencies {
    implementation(project(":lightbatis-annotations"))
    // square/javapoet is archived since 10/2024; the Palantir fork is the
    // maintained one (build plan §01). Kept behind emit.SourceWriter so a
    // hand-rolled emitter can replace it later.
    implementation("com.palantir.javapoet:javapoet:0.7.0")

    // Tests compile sample mappers in-memory and assert on generated sources;
    // the generated code references the runtime, so it joins the test classpath.
    testImplementation(project(":lightbatis-runtime"))
    testImplementation("com.google.testing.compile:compile-testing:0.21.0")
}

// compile-testing reaches into javac internals.
tasks.withType<Test>().configureEach {
    // golden files live in the source tree; refresh with ./gradlew test -Pupdate-golden
    systemProperty(
        "lightbatis.golden.dir",
        layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath
    )
    if (project.hasProperty("update-golden")) {
        systemProperty("lightbatis.golden.update", "true")
        outputs.upToDateWhen { false }
    }
    jvmArgs(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
