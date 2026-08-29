package io.github.lightbatis.conformance;

import io.github.lightbatis.runtime.JdbcLightBatisSession;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Runs the same mapper call through both frameworks over a
 * {@link RecordingDataSource} each, and hands back what each side did
 * (build plan §03, figure 2). MyBatis 3.5.19 is the oracle — considered
 * correct by definition.
 */
public final class DifferentialHarness {

    private DifferentialHarness() {
    }

    /** ①②③ recorded from MyBatis executing one mapper call. */
    public static <M> Recording mybatis(Class<M> mapperInterface, Consumer<M> call) {
        Recording recording = new Recording();
        Configuration configuration = new Configuration(
                new Environment("conformance", new JdbcTransactionFactory(),
                        new RecordingDataSource(recording)));
        configuration.addMapper(mapperInterface);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession()) {
            call.accept(session.getMapper(mapperInterface));
        }
        return recording;
    }

    /** ①②③ recorded from LightBatis-generated code executing one mapper call. */
    public static <M> Recording lightbatis(
            Function<JdbcLightBatisSession, M> implFactory, Consumer<M> call) {
        Recording recording = new Recording();
        JdbcLightBatisSession session =
                new JdbcLightBatisSession(new RecordingDataSource(recording));
        call.accept(implFactory.apply(session));
        return recording;
    }
}
