// JMH benchmark module: turns the projected numbers into measured ones, and
// closes the four gaps nothing had measured — startup time, single-query
// latency with a real round trip, megamorphic behaviour, and the benchmark
// hygiene itself (bean count, column count, how allocation was measured,
// which JDK).
//
// Never published. MyBatis is the comparison subject and must never appear on
// a LarkBatis runtime classpath; this module is not on one.
//
//   ./gradlew :larkbatis-benchmarks:jmh                       # everything
//   ./gradlew :larkbatis-benchmarks:jmh -Pbench=RowRead       # one class
//   ./gradlew :larkbatis-benchmarks:jmh -PbenchJdk=21         # another JDK
//   ./gradlew :larkbatis-benchmarks:jmh -Pquick               # smoke run
dependencies {
    implementation(project(":larkbatis-annotations"))
    implementation(project(":larkbatis-runtime"))
    annotationProcessor(project(":larkbatis-processor"))

    // The subject under comparison, same release the differential harness
    // uses as its executable oracle.
    implementation("org.mybatis:mybatis:3.5.19")
    implementation("com.h2database:h2:2.3.232")

    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// --- the 50 result classes of the megamorphic experiment ------------------
// Written rather than hand-maintained: the point is 50 *distinct* classes with
// the same shape, so every one of them is boilerplate by construction. The
// sources are built at configuration time into plain strings so the task
// action captures nothing but data (Gradle configuration cache).
val megaIds: List<String> = (0 until 50).map { "%02d".format(it) }

val megaSources: Map<String, String> = buildMap {
    megaIds.forEach { id -> put("MegaBean$id.java", megaBeanSource(id)) }
    put("LarkBatisMegaMapper.java", larkBatisMegaMapperSource(megaIds))
    put("MyBatisMegaMapper.java", myBatisMegaMapperSource(megaIds))
    put("MegamorphicBenchmark.java", megamorphicBenchmarkSource(megaIds))
}

val megaOut = layout.buildDirectory.dir("generated/sources/mega/java/main")

val generateMegaSources = tasks.register("generateMegaSources") {
    description = "Generate the ${megaIds.size} result classes of the megamorphic benchmark"
    outputs.dir(megaOut)
    val out = megaOut
    val sources = megaSources
    doLast {
        val dir = out.get().asFile.resolve("io/github/larkbatis/bench/mega")
        dir.deleteRecursively()
        dir.mkdirs()
        sources.forEach { (name, text) -> dir.resolve(name).writeText(text) }
    }
}

sourceSets["main"].java.srcDir(generateMegaSources)

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add(
        "-Alarkbatis.mapperDir="
            + layout.projectDirectory.dir("src/main/resources/mappers").asFile.absolutePath
    )
    inputs.dir(layout.projectDirectory.dir("src/main/resources/mappers"))
}

// --- running ---------------------------------------------------------------

tasks.register<JavaExec>("jmh") {
    description = "Run the JMH benchmarks"
    group = "verification"
    dependsOn(tasks.named("classes"))
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets["main"].runtimeClasspath

    // JMH forks a JVM per benchmark and inherits the one running it, so
    // selecting a launcher here selects the JDK the numbers describe.
    val requested = (project.findProperty("benchJdk") as String?)?.toInt() ?: 17
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(requested)
    }

    val results = layout.buildDirectory.file("reports/jmh/jmh-jdk$requested.json")
    outputs.file(results)
    // A measurement is never up to date: the machine it ran on is an input
    // Gradle cannot see, and re-running is the entire point of the task.
    outputs.upToDateWhen { false }

    val quick = project.hasProperty("quick")
    val filter = project.findProperty("bench") as String?

    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            if (filter != null) add(filter)
                    // gc.alloc.rate.norm — bytes allocated per operation. Every
            // allocation number published anywhere comes from here, which is
            // why the run is JMH and not a hand loop.
            add("-prof"); add("gc")
            add("-rf"); add("json")
            add("-rff"); add(results.get().asFile.absolutePath)
            if (quick) {
                add("-wi"); add("1")
                add("-i"); add("2")
                add("-f"); add("1")
                add("-r"); add("1s")
                add("-w"); add("1s")
            }
        }
    })

    doFirst { results.get().asFile.parentFile.mkdirs() }
}

// --- generators ------------------------------------------------------------
// The 50 beans differ only in their name. That is the whole experiment: the
// call sites inside MyBatis's BeanWrapper/MethodInvoker see 50 receiver types
// and stop being inlinable, while generated readers stay 50 separate
// monomorphic methods.

fun megaBeanSource(id: String): String = """
package io.github.larkbatis.bench.mega;

import java.time.Instant;

/** Generated for the megamorphic benchmark — do not edit. */
public class MegaBean$id {

    private long id;
    private String name;
    private String code;
    private Double amount;
    private Boolean flag;
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Boolean getFlag() {
        return flag;
    }

    public void setFlag(Boolean flag) {
        this.flag = flag;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
""".trimStart()

fun larkBatisMegaMapperSource(ids: List<String>): String {
    val methods = ids.joinToString("\n") { id ->
        """
    @Select("SELECT id, name, code, amount, flag, created_at FROM mega WHERE id = #{id}")
    MegaBean$id find$id(long id);
"""
    }
    return """
package io.github.larkbatis.bench.mega;

import io.github.larkbatis.annotations.Select;

/** Generated for the megamorphic benchmark — do not edit. */
public interface LarkBatisMegaMapper {
$methods}
""".trimStart()
}

fun myBatisMegaMapperSource(ids: List<String>): String {
    val methods = ids.joinToString("\n") { id ->
        """
    @Select("SELECT id, name, code, amount, flag, created_at FROM mega WHERE id = #{id}")
    MegaBean$id find$id(long id);
"""
    }
    return """
package io.github.larkbatis.bench.mega;

import org.apache.ibatis.annotations.Select;

/** Generated for the megamorphic benchmark — do not edit. */
public interface MyBatisMegaMapper {
$methods}
""".trimStart()
}

// The 50 call sites are written out rather than looped over: reaching them by
// reflection would put the very cost the benchmark is about back into both
// sides. `mono` calls one method 50 times, `mega` calls 50 methods once — the
// ratio between them is the megamorphic penalty, per framework.
fun megamorphicBenchmarkSource(ids: List<String>): String {
    fun mega(target: String) = ids.joinToString("\n") {
        "        blackhole.consume($target.find$it(${it.toInt()}L));"
    }
    fun mono(target: String) = ids.indices.joinToString("\n") {
        "        blackhole.consume($target.find00(${it}L));"
    }
    return """
package io.github.larkbatis.bench.mega;

import io.github.larkbatis.bench.BenchDb;
import io.github.larkbatis.bench.LarkBatisMappers;
import io.github.larkbatis.bench.MyBatisSetup;
import io.github.larkbatis.bench.PinnedSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The megamorphic experiment — generated, do not edit (see build.gradle.kts).
 *
 * <p>Nothing had measured this before, and the mechanism it tests is this:
 * with hundreds of mappers the call sites inside MyBatis's {@code BeanWrapper}
 * and {@code MethodInvoker} see many receiver types, the JIT stops inlining
 * them, and the gap the POC measured on one bean type should widen. The
 * generated readers have no shared call site to pollute, so their two numbers
 * should stay together.
 *
 * <p>Each pair is 50 single-row reads of a 6-column table. {@code mono} reads
 * one result class 50 times; {@code mega} reads ${ids.size} different result
 * classes once each. Compare {@code mega/mono} within a framework, not across.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class MegamorphicBenchmark {

    private Connection connection;
    private SqlSession mybatisSession;
    private LarkBatisMegaMapper larkbatis;
    private MyBatisMegaMapper mybatis;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        JdbcDataSource dataSource = BenchDb.memory("mega");
        BenchDb.seed(dataSource, 1);
        connection = dataSource.getConnection();
        larkbatis = LarkBatisMappers.larkBatisMegaMapper(new PinnedSession(connection));
        SqlSessionFactory factory = MyBatisSetup.factory(dataSource, MyBatisMegaMapper.class);
        mybatisSession = factory.openSession();
        mybatis = mybatisSession.getMapper(MyBatisMegaMapper.class);
        if (larkbatis.find00(0L) == null || mybatis.find00(0L) == null) {
            throw new IllegalStateException("mega table not seeded");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        mybatisSession.close();
        connection.close();
    }

    @Benchmark
    public void larkbatisMonomorphic(Blackhole blackhole) {
${mono("larkbatis")}
    }

    @Benchmark
    public void larkbatisMegamorphic(Blackhole blackhole) {
${mega("larkbatis")}
    }

    @Benchmark
    public void mybatisMonomorphic(Blackhole blackhole) {
${mono("mybatis")}
    }

    @Benchmark
    public void mybatisMegamorphic(Blackhole blackhole) {
${mega("mybatis")}
    }
}
""".trimStart()
}
