package io.github.larkbatis.bench;

import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Builds the MyBatis side of every comparison, programmatically — no
 * mybatis-config.xml, so the only difference between the two sides is the
 * mapper layer itself.
 *
 * <p>Two settings are deliberately not left at their defaults, and both are
 * about measuring the same thing on both sides rather than about flattering
 * either:
 *
 * <ul>
 *   <li><b>{@code localCacheScope = STATEMENT}.</b> MyBatis's first-level
 *       cache is on by default at SESSION scope, and every benchmark here
 *       holds one {@code SqlSession} open for the whole trial. Left alone, the
 *       second call to {@code findById(1)} would return the cached object
 *       without touching JDBC at all, and the benchmark would be measuring a
 *       HashMap lookup. LarkBatis has no first-level cache to turn off.</li>
 *   <li><b>{@code cacheEnabled = false}.</b> The second-level cache is a
 *       feature LarkBatis dropped outright; leaving it on would compare two
 *       different feature sets.</li>
 * </ul>
 *
 * <p>{@code mapUnderscoreToCamelCase} is on because LarkBatis applies it at
 * build time, always — {@code created_at} has to reach {@code createdAt} on
 * both sides or the comparison is between a populated bean and a half-empty
 * one.
 */
public final class MyBatisSetup {

    private MyBatisSetup() {
    }

    public static SqlSessionFactory factory(DataSource dataSource, Class<?>... mappers) {
        return new SqlSessionFactoryBuilder().build(configuration(dataSource, mappers));
    }

    public static Configuration configuration(DataSource dataSource, Class<?>... mappers) {
        Environment environment =
                new Environment("bench", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
        return configuration;
    }
}
