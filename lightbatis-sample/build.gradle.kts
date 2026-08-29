// Sample application (build plan §05, task 13): a real javac run through the
// annotation processor, executed against H2 — the whole chain outside
// compile-testing. Never published. Also the native-image smoke-test subject:
// no reflection anywhere, so `native-image` needs no reflect-config for the
// mapper layer (GraalVM is not installed on this machine yet — deferred).
plugins {
    application
}

dependencies {
    implementation(project(":lightbatis-annotations"))
    implementation(project(":lightbatis-runtime"))
    annotationProcessor(project(":lightbatis-processor"))

    // JdbcDataSource is constructed directly — no DriverManager, no Class.forName
    implementation("com.h2database:h2:2.3.232")
}

application {
    mainClass = "com.example.lbsample.SampleApp"
}
