package io.github.larkbatis.bench;

import io.github.larkbatis.bench.mega.MyBatisMegaMapper;
import io.github.larkbatis.runtime.JdbcLarkBatisSession;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Startup — "easiest remaining number to measure", and the one it
 * suspects is real but usually buried under Spring context creation.
 *
 * <p>The measurement is one shot in a fresh JVM ({@code @Fork(10)},
 * {@code @Warmup(0)}, one iteration each), so class loading counts, which is
 * most of the point: on the MyBatis side that means loading the builder, the
 * OGNL and XPath machinery and the type-handler registry; on the LarkBatis
 * side it means loading four generated classes.
 *
 * <p>Both sides bring up the same application shape — four mapper interfaces,
 * one of which carries 50 statements, and one mapper XML — and both end with a
 * real query, so neither can be "started" without having done the work.
 * LarkBatis goes through the real {@link JdbcLarkBatisSession} here rather
 * than {@link PinnedSession}: connection setup is part of starting up.
 *
 * <p>The database is created in {@code @Setup}, outside the measurement; H2's
 * own class loading lands on both sides equally.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(10)
@Threads(1)
public class StartupBenchmark {

    private JdbcDataSource dataSource;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        dataSource = BenchDb.memory("startup");
        BenchDb.seed(dataSource, 10);
    }

    @Benchmark
    public NarrowRow mybatisColdStart() throws Exception {
        Configuration configuration = MyBatisSetup.configuration(dataSource,
                MyBatisNarrowMapper.class, MyBatisWideMapper.class, MyBatisMegaMapper.class);
        try (InputStream xml = StartupBenchmark.class
                .getResourceAsStream("/mybatis/SearchMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mybatis/SearchMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        try (SqlSession session =
                new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            return session.getMapper(MyBatisNarrowMapper.class).findById(1);
        }
    }

    @Benchmark
    public NarrowRow larkbatisColdStart() {
        JdbcLarkBatisSession session = new JdbcLarkBatisSession(dataSource);
        LarkBatisMappers.larkBatisWideMapper(session);
        LarkBatisMappers.larkBatisMegaMapper(session);
        LarkBatisMappers.larkBatisSearchMapper(session);
        return LarkBatisMappers.larkBatisNarrowMapper(session).findById(1);
    }
}
