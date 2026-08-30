package io.github.larkbatis.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.larkbatis.processor.frontend.TypeResolver;
import io.github.larkbatis.processor.ir.MapperModel;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.Modifier;

/**
 * Emits {@code LarkBatisMapperConfiguration}: one {@code @Bean} per mapper
 * — this is the whole of what {@code @MapperScan} +
 * {@code ClassPathMapperScanner} + {@code MapperFactoryBean} did, because a
 * mapper impl is an ordinary class with an ordinary constructor — "create an
 * object from an interface" is the problem a DI container exists to solve,
 * and codegen already solved it.
 *
 * <p>Emitted only when spring-context is on the build classpath, so a
 * non-Spring build never sees this file.
 *
 * <p>{@code proxyBeanMethods = false} is load-bearing, not style: the default
 * {@code true} makes Spring build a CGLIB subclass of this very class at
 * runtime — the exact kind of runtime bytecode generation the project exists
 * to remove. No {@code @Bean} here calls another, so turning it off is both
 * correct and free. The static return types are also what makes Spring AOT
 * treat these as ordinary beans: no {@code getObjectType()} to call at
 * runtime, no proxy hint to write.
 */
public final class SpringConfigurationEmitter {

    public static final String CONFIGURATION_SIMPLE_NAME = "LarkBatisMapperConfiguration";

    private static final ClassName CONFIGURATION =
            ClassName.get("org.springframework.context.annotation", "Configuration");
    private static final ClassName BEAN =
            ClassName.get("org.springframework.context.annotation", "Bean");

    private SpringConfigurationEmitter() {
    }

    public static String emit(String packageName, List<MapperModel> mappers) {
        TypeSpec.Builder type = TypeSpec.classBuilder(CONFIGURATION_SIMPLE_NAME)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(EmitSupport.generatedAnnotation())
                .addAnnotation(AnnotationSpec.builder(CONFIGURATION)
                        .addMember("proxyBeanMethods", "false")
                        .build())
                .addJavadoc("Every LarkBatis mapper as a Spring bean.\n"
                        + "Generated — do not edit.\n\n"
                        + "<p>Lands in the application's base package so the default\n"
                        + "{@code @ComponentScan} of {@code @SpringBootApplication} picks it up.\n"
                        + "A project with an unusual package layout either sets\n"
                        + "{@code -Alarkbatis.springConfigPackage=...} or imports it explicitly\n"
                        + "with {@code @Import(LarkBatisMapperConfiguration.class)}.\n\n"
                        + "<p>{@code proxyBeanMethods = false}: no {@code @Bean} here calls\n"
                        + "another, so the runtime CGLIB subclass Spring would otherwise build\n"
                        + "for this class is pure cost — and exactly the runtime bytecode\n"
                        + "generation LarkBatis exists to remove.\n");

        Map<String, String> beanNames = beanNames(mappers);
        for (MapperModel mapper : mappers) {
            ClassName mapperInterface = ClassName.get(mapper.packageName(), mapper.simpleName());
            ClassName impl = ClassName.get(mapper.packageName(), mapper.implSimpleName());
            type.addMethod(MethodSpec.methodBuilder(beanNames.get(mapper.interfaceFqn()))
                    .addAnnotation(BEAN)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(mapperInterface)
                    .addParameter(EmitSupport.LIGHT_BATIS_SESSION, "s")
                    .addStatement("return new $T(s)", impl)
                    .build());
        }

        return JavaFile.builder(packageName, type.build())
                .skipJavaLangImports(true)
                .indent("    ")
                .build()
                .toString();
    }

    /**
     * Bean name per mapper. Spring bean names must be unique and the obvious
     * one — the decapitalized simple name — is not, because two packages may
     * each hold a {@code UserMapper}. Only the colliding names get widened,
     * so the common case still reads {@code userMapper}.
     */
    private static Map<String, String> beanNames(List<MapperModel> mappers) {
        Map<String, Integer> counts = new HashMap<>();
        for (MapperModel mapper : mappers) {
            counts.merge(TypeResolver.decapitalize(mapper.simpleName()), 1, Integer::sum);
        }
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> widenedCounts = new HashMap<>();
        for (MapperModel mapper : mappers) {
            String plain = TypeResolver.decapitalize(mapper.simpleName());
            String name = counts.get(plain) == 1 ? plain
                    : TypeResolver.decapitalize(lastSegment(mapper.packageName())
                            + mapper.simpleName());
            widenedCounts.merge(name, 1, Integer::sum);
            names.put(mapper.interfaceFqn(), name);
        }
        // two packages whose last segment matches too (a.web.UserMapper and
        // b.web.UserMapper): fall back to the one name that cannot collide
        for (MapperModel mapper : mappers) {
            String name = names.get(mapper.interfaceFqn());
            if (widenedCounts.get(name) > 1) {
                names.put(mapper.interfaceFqn(),
                        TypeResolver.decapitalize(mapper.interfaceFqn().replace('.', '_')));
            }
        }
        return names;
    }

    private static String lastSegment(String packageName) {
        int dot = packageName.lastIndexOf('.');
        String segment = dot < 0 ? packageName : packageName.substring(dot + 1);
        return segment.isEmpty() ? "" : segment.substring(0, 1).toUpperCase(Locale.ROOT)
                + segment.substring(1);
    }
}
