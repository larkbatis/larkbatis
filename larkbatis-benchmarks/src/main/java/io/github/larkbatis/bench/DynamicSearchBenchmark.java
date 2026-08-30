package io.github.larkbatis.bench;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The dynamic-SQL path: three {@code <if>} branches inside a {@code <where>},
 * compiled to {@code boolean} locals and a {@code StringBuilder} by LarkBatis,
 * walked as an OGNL-evaluated SqlNode tree by MyBatis.
 *
 * <p>The POC measured 15–20 µs → 9,5 µs here, and the surprise is worth
 * keeping in mind when reading the numbers: the dynamic path gains
 * proportionally <em>less</em> than the row-read path.
 *
 * <p>{@code filters} selects how many branches are live, because that is what
 * changes the interpreter's work: {@code none} evaluates three tests and emits
 * nothing, {@code all} evaluates three and emits three.
 *
 * <p><b>Every setting returns exactly one row.</b> The statement pins
 * {@code id = #{pinnedId}} and the optional predicates are all non-restrictive,
 * so row reading is a constant and the difference between the three cases is
 * dynamic SQL and nothing else. The first version of this benchmark let the
 * row count vary from 100 to 1 across the settings, which made two of the three
 * numbers a restatement of the row-read result — the setup asserts the count
 * now, so that cannot come back unnoticed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class DynamicSearchBenchmark {

    private static final int ROWS = 100;

    @Param({"none", "name", "all"})
    public String filters;

    private Connection connection;
    private SqlSession mybatisSession;
    private LarkBatisSearchMapper larkbatis;
    private MyBatisSearchMapper mybatis;
    private SearchQuery query;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        JdbcDataSource dataSource = BenchDb.memory("search");
        BenchDb.seed(dataSource, ROWS);

        connection = dataSource.getConnection();
        larkbatis = LarkBatisMappers.larkBatisSearchMapper(new PinnedSession(connection));

        Configuration configuration = MyBatisSetup.configuration(dataSource);
        // XMLMapperBuilder.parse() binds the namespace to its interface itself,
        // so there is no addMapper call to forget here.
        try (InputStream xml = DynamicSearchBenchmark.class
                .getResourceAsStream("/mybatis/SearchMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mybatis/SearchMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        mybatisSession = new SqlSessionFactoryBuilder().build(configuration).openSession();
        mybatis = mybatisSession.getMapper(MyBatisSearchMapper.class);

        query = new SearchQuery();
        // Row 7 exists and has a non-null email (only every eighth row is
        // null), so every filter below matches it and nothing else.
        query.setPinnedId(7);
        switch (filters) {
            case "none" -> { }
            case "name" -> query.setName("%");
            case "all" -> {
                query.setName("%");
                query.setEmail("%");
                query.setMinId(1);
            }
            default -> throw new IllegalArgumentException(filters);
        }

        int larkbatisRows = larkbatis.search(query).size();
        int mybatisRows = mybatis.search(query).size();
        if (larkbatisRows != mybatisRows) {
            throw new IllegalStateException("the two sides disagree: larkbatis="
                    + larkbatisRows + " mybatis=" + mybatisRows);
        }
        // The whole point of the pinned id: if a filter setting ever changed
        // the row count, this benchmark would silently go back to measuring
        // row reading and the numbers would look like a dynamic-SQL result.
        if (larkbatisRows != 1) {
            throw new IllegalStateException("filters=" + filters + " returned "
                    + larkbatisRows + " rows; every setting must return exactly 1"
                    + " or the cases are not comparable");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        mybatisSession.close();
        connection.close();
    }

    @Benchmark
    public List<NarrowRow> larkbatisSearch() {
        return larkbatis.search(query);
    }

    @Benchmark
    public List<NarrowRow> mybatisSearch() {
        return mybatis.search(query);
    }
}
