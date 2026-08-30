// Modules that go to Maven Central. Everything else in this build is a sample,
// a differential harness or a benchmark, and publishing one would put MyBatis
// or H2 into a consumer's dependency graph — see README.md for the table.
val publishedProjects = listOf(
    "larkbatis-annotations",
    "larkbatis-runtime",
    "larkbatis-processor",
    "larkbatis-scanner",
)

subprojects {
    apply(plugin = "java-library")

    group = "io.github.larkbatis"
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
        withSourcesJar()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Gradle incremental builds re-run aggregating processors over UNCHANGED
    // mappers from their class files, where parameter names only survive with
    // -parameters. Clean builds never need this; incremental ones do.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// --- publishing ------------------------------------------------------------
//
// The Central Portal takes a *zipped bundle* in Maven repository layout, not a
// deploy over the wire, so the release path is two steps: publish into
// build/central-bundle (a local Maven layout, checksums and signatures
// included), then hand that directory to .github/scripts/publish-to-central.sh.
// The snapshot repository is a normal remote and needs neither step nor a
// signature.

configure(publishedProjects.map { project(":$it") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    configure<JavaPluginExtension> {
        withJavadocJar()
    }

    // Central rejects a POM without a description, and a build that discovers
    // that at upload time has already spent five minutes getting there.
    afterEvaluate {
        require(!description.isNullOrBlank()) {
            "$path is published to Maven Central and must set `description` in its build file"
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name = project.name
                    description = provider { project.description }
                    url = "https://github.com/larkbatis/larkbatis"
                    inceptionYear = "2026"
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            distribution = "repo"
                        }
                    }
                    developers {
                        developer {
                            id = "larkbatis"
                            name = "LarkBatis contributors"
                            url = "https://github.com/larkbatis"
                        }
                    }
                    scm {
                        connection = "scm:git:https://github.com/larkbatis/larkbatis.git"
                        developerConnection = "scm:git:ssh://git@github.com/larkbatis/larkbatis.git"
                        url = "https://github.com/larkbatis/larkbatis"
                    }
                    issueManagement {
                        system = "GitHub Issues"
                        url = "https://github.com/larkbatis/larkbatis/issues"
                    }
                }
            }
        }

        repositories {
            // One directory for the whole build, so a single zip carries every
            // module of a release and the Portal validates them together.
            maven {
                name = "centralBundle"
                url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
            }
            maven {
                name = "centralSnapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                credentials {
                    username = providers.environmentVariable("CENTRAL_USERNAME").orNull
                    password = providers.environmentVariable("CENTRAL_PASSWORD").orNull
                }
            }
        }
    }

    configure<SigningExtension> {
        // CI passes an ASCII-armoured secret key through the environment. A
        // developer without those variables still gets a working
        // `publishToMavenLocal` and a working snapshot publish, because neither
        // needs a signature — only the Central bundle does, and the release
        // workflow asserts the .asc files exist before it uploads.
        val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
        val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
        isRequired = signingKey != null
        if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(the<PublishingExtension>().publications["maven"])
        }
    }
}
