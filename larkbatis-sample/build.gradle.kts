// Sample application: a real javac run through the
// annotation processor, executed against H2 — the whole chain outside
// compile-testing. Never published. Also the native-image smoke-test subject:
// no reflection anywhere, so `native-image` needs no reflect-config for the
// mapper layer (GraalVM is not installed on this machine yet — deferred).
plugins {
    application
}

dependencies {
    implementation(project(":larkbatis-annotations"))
    implementation(project(":larkbatis-runtime"))
    annotationProcessor(project(":larkbatis-processor"))

    // JdbcDataSource is constructed directly — no DriverManager, no Class.forName
    implementation("com.h2database:h2:2.3.232")
}

application {
    mainClass = "com.example.lbsample.SampleApp"
}

// Mapper XML lives in src/main/resources; the processor cannot see
// resources through Filer, so the directory is passed explicitly (the build
// plugins will do this automatically).
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add(
        "-Alarkbatis.mapperDir=" + layout.projectDirectory.dir("src/main/resources/mappers").asFile.absolutePath
    )
    // the XML shapes the generated sources; recompile when it changes
    inputs.dir(layout.projectDirectory.dir("src/main/resources/mappers"))
}
