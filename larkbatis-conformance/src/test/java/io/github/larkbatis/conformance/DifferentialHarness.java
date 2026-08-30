package io.github.larkbatis.conformance;

import io.github.larkbatis.runtime.JdbcLarkBatisSession;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Runs the same mapper call through both frameworks over a
 * {@link RecordingDataSource} each, and hands back what each side did.
 * MyBatis 3.5.19 is the oracle — considered
 * correct by definition.
 */
public final class DifferentialHarness {

    private DifferentialHarness() {
    }

    /** ①②③ recorded from MyBatis executing one mapper call. */
    public static <M> Recording mybatis(Class<M> mapperInterface, Consumer<M> call) {
        return mybatis(mapperInterface, call, false);
    }

    /**
     * XML fixtures pass {@code shrinkWhitespace = true}: MyBatis keeps the
     * raw XML whitespace inside each text node while LarkBatis normalizes at
     * build time. shrinkWhitespacesInSql collapses the oracle's
     * SQL the same way, so the comparison stays char-for-char — whitespace is
     * the one documented divergence, everything else must match exactly.
     */
    public static <M> Recording mybatis(Class<M> mapperInterface, Consumer<M> call,
            boolean shrinkWhitespace) {
        Recording recording = new Recording();
        Configuration configuration = new Configuration(
                new Environment("conformance", new JdbcTransactionFactory(),
                        new RecordingDataSource(recording)));
        configuration.setShrinkWhitespacesInSql(shrinkWhitespace);
        configuration.addMapper(mapperInterface);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession()) {
            call.accept(session.getMapper(mapperInterface));
        }
        return recording;
    }

    /**
     * The other axis of the harness: a real database instead of a
     * recorder, and the *result* instead of the SQL. Nested result maps emit
     * the same SQL through both frameworks — what can differ is how the join's
     * rows collapse back into objects, and only rows can show that.
     */
    public static <M, R> R mybatisResult(DataSource dataSource, Class<M> mapperInterface,
            Function<M, R> call) {
        Configuration configuration = new Configuration(
                new Environment("conformance", new JdbcTransactionFactory(), dataSource));
        configuration.setShrinkWhitespacesInSql(true);
        configuration.addMapper(mapperInterface);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession()) {
            return call.apply(session.getMapper(mapperInterface));
        }
    }

    /** The LarkBatis side of {@link #mybatisResult}. */
    public static <M, R> R larkbatisResult(DataSource dataSource,
            Function<JdbcLarkBatisSession, M> implFactory, Function<M, R> call) {
        return call.apply(implFactory.apply(new JdbcLarkBatisSession(dataSource)));
    }

    /** ①②③ recorded from LarkBatis-generated code executing one mapper call. */
    public static <M> Recording larkbatis(
            Function<JdbcLarkBatisSession, M> implFactory, Consumer<M> call) {
        Recording recording = new Recording();
        JdbcLarkBatisSession session =
                new JdbcLarkBatisSession(new RecordingDataSource(recording));
        call.accept(implFactory.apply(session));
        return recording;
    }
}
