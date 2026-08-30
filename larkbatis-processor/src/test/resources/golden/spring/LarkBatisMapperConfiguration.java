package com.example.app;

import io.github.larkbatis.runtime.LarkBatisSession;
import javax.annotation.processing.Generated;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every LarkBatis mapper as a Spring bean.
 * Generated — do not edit.
 *
 * <p>Lands in the application's base package so the default
 * {@code @ComponentScan} of {@code @SpringBootApplication} picks it up.
 * A project with an unusual package layout either sets
 * {@code -Alarkbatis.springConfigPackage=...} or imports it explicitly
 * with {@code @Import(LarkBatisMapperConfiguration.class)}.
 *
 * <p>{@code proxyBeanMethods = false}: no {@code @Bean} here calls
 * another, so the runtime CGLIB subclass Spring would otherwise build
 * for this class is pure cost — and exactly the runtime bytecode
 * generation LarkBatis exists to remove.
 */
@Generated("io.github.larkbatis.processor.LarkBatisProcessor")
@Configuration(
        proxyBeanMethods = false
)
public class LarkBatisMapperConfiguration {
    @Bean
    public UserMapper userMapper(LarkBatisSession s) {
        return new UserMapper$$Impl(s);
    }
}
