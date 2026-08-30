package io.github.larkbatis.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.larkbatis.processor.frontend.TypeResolver;
import io.github.larkbatis.processor.ir.MapperModel;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * Emits {@code LarkBatisMappers}: the static, closed list of mappers
 * (it replaces classpath scanning; nothing is registered at
 * runtime). One factory method per mapper.
 */
public final class RegistryEmitter {

    public static final String REGISTRY_SIMPLE_NAME = "LarkBatisMappers";

    private RegistryEmitter() {
    }

    public static String emit(String packageName, List<MapperModel> mappers) {
        TypeSpec.Builder type = TypeSpec.classBuilder(REGISTRY_SIMPLE_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(EmitSupport.generatedAnnotation())
                .addJavadoc("The closed set of LarkBatis mappers, known at compile time"
                        + ".\nGenerated — do not edit.\n");

        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        for (MapperModel mapper : mappers) {
            ClassName mapperInterface = ClassName.get(mapper.packageName(), mapper.simpleName());
            ClassName impl = ClassName.get(mapper.packageName(), mapper.implSimpleName());
            type.addMethod(MethodSpec.methodBuilder(TypeResolver.decapitalize(mapper.simpleName()))
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
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
}
