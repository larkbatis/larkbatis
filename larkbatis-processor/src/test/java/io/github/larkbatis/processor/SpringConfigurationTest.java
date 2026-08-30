package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertGolden;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixtures;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated Spring {@code @Configuration} — the replacement
 * for {@code @MapperScan} + {@code ClassPathMapperScanner} +
 * {@code MapperFactoryBean}. spring-context is on this module's test
 * classpath, which is exactly the trigger the processor looks for.
 */
class SpringConfigurationTest {

    @Test
    void oneBeanPerMapper() {
        Compilation compilation = compileFixtures(
                "com/example/app/User.java", "com/example/app/UserMapper.java");
        assertSucceeded(compilation);
        assertGolden("spring/LarkBatisMapperConfiguration.java",
                generatedSource(compilation, "com.example.app.LarkBatisMapperConfiguration"));
    }

    @Test
    void suppressedByOption() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.springConfig=false"),
                "com/example/app/User.java", "com/example/app/UserMapper.java");
        assertSucceeded(compilation);
        assertFalse(generatedFileNames(compilation).contains("LarkBatisMapperConfiguration.java"),
                () -> "springConfig=false still emitted the configuration: "
                        + generatedFileNames(compilation));
        // the rest of the generation is untouched by the option
        assertTrue(generatedFileNames(compilation).contains("LarkBatisMappers.java"));
    }

    @Test
    void landsInTheConfiguredPackage() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.springConfigPackage=com.example"),
                "com/example/app/User.java", "com/example/app/UserMapper.java");
        assertSucceeded(compilation);
        // generatedSource fails the test if the file is not there
        assertTrue(generatedSource(compilation, "com.example.LarkBatisMapperConfiguration")
                .contains("package com.example;"));
    }

    private static String generatedFileNames(Compilation compilation) {
        return compilation.generatedSourceFiles().stream()
                .map(JavaFileObject::getName)
                .reduce("", (a, b) -> a + " " + b);
    }
}
