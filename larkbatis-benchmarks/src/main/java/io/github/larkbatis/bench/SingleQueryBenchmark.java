package io.github.larkbatis.bench;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.tools.Server;
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
 * The honest half of the migration pitch (gap b): a
 * <em>single-row</em> lookup, where the saving is a few hundred nanoseconds
 * against a round trip, and the expected result is "no measurable difference".
 *
 * <p>{@code transport=mem} is H2 in process — no socket at all, the most
 * favourable case for showing a mapper-layer difference. {@code transport=tcp}
 * puts H2's TCP server and the H2 wire protocol between the two, over
 * loopback. That is not MySQL on another host, and the report must not claim
 * it is; it is the cheapest possible real round trip, which makes it a
 * <em>lower bound</em> on how far a real database drowns the mapper layer. If
 * the difference is already noise here, it is noise everywhere slower.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class SingleQueryBenchmark {

    @Param({"mem", "tcp"})
    public String transport;

    private Server server;
    private Connection connection;
    private SqlSession mybatisSession;
    private LarkBatisNarrowMapper larkbatis;
    private MyBatisNarrowMapper mybatis;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        DataSource dataSource;
        if ("tcp".equals(transport)) {
            int port = freePort();
            server = Server.createTcpServer("-tcpPort", Integer.toString(port),
                    "-tcpDaemon", "-ifNotExists").start();
            dataSource = BenchDb.tcp(server.getPort(), "single");
        } else {
            dataSource = BenchDb.memory("single");
        }
        BenchDb.seed(dataSource, 1000);

        connection = dataSource.getConnection();
        larkbatis = LarkBatisMappers.larkBatisNarrowMapper(new PinnedSession(connection));

        SqlSessionFactory factory = MyBatisSetup.factory(dataSource, MyBatisNarrowMapper.class);
        mybatisSession = factory.openSession();
        mybatis = mybatisSession.getMapper(MyBatisNarrowMapper.class);

        if (larkbatis.findById(7) == null || mybatis.findById(7) == null) {
            throw new IllegalStateException("row 7 missing over " + transport);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        mybatisSession.close();
        connection.close();
        if (server != null) {
            server.stop();
        }
    }

    @Benchmark
    public NarrowRow larkbatisFindById() {
        return larkbatis.findById(7);
    }

    @Benchmark
    public NarrowRow mybatisFindById() {
        return mybatis.findById(7);
    }

    /**
     * H2's own auto-port handling differs across versions; asking the OS for a
     * free port and handing it over is version-independent. The gap between
     * closing this socket and H2 binding it is a race no benchmark JVM shares
     * with anything else.
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
