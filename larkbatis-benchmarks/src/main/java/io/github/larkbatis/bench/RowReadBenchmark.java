package io.github.larkbatis.bench;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The row-read path — the headline claim ("−73% latency, −87% allocation per
 * row" on the codegen POC), re-measured on LarkBatis itself.
 *
 * <p>Two axes, both of which move the result: the row count, because the
 * saving is proportional to it, and the column count ({@link NarrowRow} = 4,
 * {@link WideRow} = 12), because both time and allocation scale with it.
 *
 * <p>Allocation comes from {@code -prof gc} → {@code gc.alloc.rate.norm},
 * bytes per operation; that is the answer to "measured with JMH or a hand
 * hand loop, and how were the allocation numbers taken".
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class RowReadBenchmark {

    @Param({"1", "100", "10000"})
    public int rows;

    private Connection connection;
    private SqlSession mybatisSession;

    private LarkBatisNarrowMapper larkbatisNarrow;
    private LarkBatisWideMapper larkbatisWide;
    private MyBatisNarrowMapper mybatisNarrow;
    private MyBatisWideMapper mybatisWide;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        JdbcDataSource dataSource = BenchDb.memory("rowread" + rows);
        BenchDb.seed(dataSource, rows);

        connection = dataSource.getConnection();
        PinnedSession session = new PinnedSession(connection);
        larkbatisNarrow = LarkBatisMappers.larkBatisNarrowMapper(session);
        larkbatisWide = LarkBatisMappers.larkBatisWideMapper(session);

        SqlSessionFactory factory = MyBatisSetup.factory(dataSource,
                MyBatisNarrowMapper.class, MyBatisWideMapper.class);
        mybatisSession = factory.openSession();
        mybatisNarrow = mybatisSession.getMapper(MyBatisNarrowMapper.class);
        mybatisWide = mybatisSession.getMapper(MyBatisWideMapper.class);

        // Same rows out of both sides, or the numbers describe different work.
        int larkbatisCount = larkbatisNarrow.findAll().size();
        int mybatisCount = mybatisNarrow.findAll().size();
        if (larkbatisCount != rows || mybatisCount != rows) {
            throw new IllegalStateException("row count mismatch: larkbatis=" + larkbatisCount
                    + " mybatis=" + mybatisCount + " expected=" + rows);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        mybatisSession.close();
        connection.close();
    }

    @Benchmark
    public List<NarrowRow> larkbatisNarrow() {
        return larkbatisNarrow.findAll();
    }

    @Benchmark
    public List<NarrowRow> mybatisNarrow() {
        return mybatisNarrow.findAll();
    }

    @Benchmark
    public List<WideRow> larkbatisWide() {
        return larkbatisWide.findAll();
    }

    @Benchmark
    public List<WideRow> mybatisWide() {
        return mybatisWide.findAll();
    }
}
