// Frontend (XML/annotations) -> MapperModel (IR) -> emitters.
// Build-only: must NEVER appear on the application's runtime classpath.

description = "LarkBatis annotation processor — compiles mappers and SQL into plain Java at build time"

dependencies {
    implementation(project(":larkbatis-annotations"))
    // square/javapoet is archived since 10/2024; the Palantir fork is the
    // maintained one. Kept behind emit.SourceWriter so a
    // hand-rolled emitter can replace it later.
    implementation("com.palantir.javapoet:javapoet:0.7.0")

    // Tests compile sample mappers in-memory and assert on generated sources;
    // the generated code references the runtime, so it joins the test classpath.
    testImplementation(project(":larkbatis-runtime"))
    testImplementation("com.google.testing.compile:compile-testing:0.21.0")
    // The generated Spring @Configuration is emitted when spring-context is
    // on the build classpath, and compile-testing compiles what we emit — so
    // spring-context has to be on the *test* classpath for that path to run at
    // all. It never reaches larkbatis-processor's own compile classpath.
    testImplementation("org.springframework:spring-context:6.1.14")
}

// compile-testing reaches into javac internals.
tasks.withType<Test>().configureEach {
    // golden files live in the source tree; refresh with ./gradlew test -Pupdate-golden
    systemProperty(
        "larkbatis.golden.dir",
        layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath
    )
    if (project.hasProperty("update-golden")) {
        systemProperty("larkbatis.golden.update", "true")
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
