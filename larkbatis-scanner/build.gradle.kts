// The legacy-mapper scanner: one command that
// reads an existing MyBatis codebase and prints what migrating it to
// LarkBatis would cost, with file and line numbers.
//
// Build-only, like the processor and the two build plugins — it must
// never reach an application's runtime classpath, and it does not depend on
// larkbatis-runtime.
//
//   ./gradlew :larkbatis-scanner:run --args="/path/to/legacy-service"
//   ./gradlew :larkbatis-scanner:installDist   # then build/install/.../bin/larkbatis-scan

plugins {
    application
}

description = "larkbatis-scan — reports what migrating an existing MyBatis codebase to LarkBatis would cost"

dependencies {
    // The frontend is the oracle: the grammar check and the XML parser the
    // scanner reports on are the same code the processor will run at build
    // time, so the report cannot drift away from what actually compiles.
    implementation(project(":larkbatis-processor"))
}

application {
    mainClass = "io.github.larkbatis.scanner.ScannerMain"
    applicationName = "larkbatis-scan"
}
