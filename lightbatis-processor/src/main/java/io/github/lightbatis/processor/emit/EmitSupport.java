package io.github.lightbatis.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import io.github.lightbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared emit vocabulary: runtime class names (referenced by name — the
 * processor has no compile dependency on the runtime, §03), type-name
 * reconstruction from IR strings, and the read/write code shapes chosen by
 * {@link ValueKind}.
 */
public final class EmitSupport {

    static final String RUNTIME_PACKAGE = "io.github.lightbatis.runtime";

    static final ClassName LIGHT_BATIS_SESSION = ClassName.get(RUNTIME_PACKAGE, "LightBatisSession");
    static final ClassName JDBC_CODEC = ClassName.get(RUNTIME_PACKAGE, "JdbcCodec");
    static final ClassName LIGHT_BATIS_SQL = ClassName.get(RUNTIME_PACKAGE, "LightBatisSql");
    static final ClassName LIGHT_BATIS_EXCEPTION = ClassName.get(RUNTIME_PACKAGE, "LightBatisException");
    static final ClassName REJECTED_EXCEPTION = ClassName.get(RUNTIME_PACKAGE, "LightBatisRejectedException");
    static final ClassName KEY_COUNT_MISMATCH = ClassName.get(RUNTIME_PACKAGE, "LightBatisKeyCountMismatchException");
    static final ClassName ROW_READER = ClassName.get(RUNTIME_PACKAGE, "RowReader");

    static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    static final ClassName PREPARED_STATEMENT = ClassName.get("java.sql", "PreparedStatement");
    static final ClassName RESULT_SET = ClassName.get("java.sql", "ResultSet");
    static final ClassName RESULT_SET_META_DATA = ClassName.get("java.sql", "ResultSetMetaData");
    static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    static final ClassName SQL_STATEMENT = ClassName.get("java.sql", "Statement");
    static final ClassName LIST = ClassName.get("java.util", "List");
    static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    static final ClassName LOCALE = ClassName.get("java.util", "Locale");

    private EmitSupport() {
    }

    static AnnotationSpec generatedAnnotation() {
        return AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                .addMember("value", "$S", "io.github.lightbatis.processor.LightBatisProcessor")
                .build();
    }

    /** Reconstructs a TypeName from the IR's string form. */
    static TypeName typeName(String fqn) {
        String trimmed = fqn.trim();
        if (trimmed.endsWith("[]")) {
            return ArrayTypeName.of(typeName(trimmed.substring(0, trimmed.length() - 2)));
        }
        switch (trimmed) {
            case "void": return TypeName.VOID;
            case "boolean": return TypeName.BOOLEAN;
            case "byte": return TypeName.BYTE;
            case "short": return TypeName.SHORT;
            case "int": return TypeName.INT;
            case "long": return TypeName.LONG;
            case "char": return TypeName.CHAR;
            case "float": return TypeName.FLOAT;
            case "double": return TypeName.DOUBLE;
            default:
                break;
        }
        int angle = trimmed.indexOf('<');
        if (angle < 0) {
            return ClassName.bestGuess(trimmed);
        }
        ClassName raw = ClassName.bestGuess(trimmed.substring(0, angle));
        String argsText = trimmed.substring(angle + 1, trimmed.lastIndexOf('>'));
        List<TypeName> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsText.length(); i++) {
            char ch = argsText.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                args.add(typeName(argsText.substring(start, i)));
                start = i + 1;
            }
        }
        args.add(typeName(argsText.substring(start)));
        return ParameterizedTypeName.get(raw, args.toArray(new TypeName[0]));
    }

    /** The Java type a scalar {@link ValueKind} reads back as. */
    static TypeName scalarTypeName(ValueKind kind, String enumType) {
        return switch (kind) {
            case PRIM_BOOLEAN -> TypeName.BOOLEAN;
            case PRIM_BYTE -> TypeName.BYTE;
            case PRIM_SHORT -> TypeName.SHORT;
            case PRIM_INT -> TypeName.INT;
            case PRIM_LONG -> TypeName.LONG;
            case PRIM_FLOAT -> TypeName.FLOAT;
            case PRIM_DOUBLE -> TypeName.DOUBLE;
            case BOX_BOOLEAN -> ClassName.get(Boolean.class);
            case BOX_BYTE -> ClassName.get(Byte.class);
            case BOX_SHORT -> ClassName.get(Short.class);
            case BOX_INT -> ClassName.get(Integer.class);
            case BOX_LONG -> ClassName.get(Long.class);
            case BOX_FLOAT -> ClassName.get(Float.class);
            case BOX_DOUBLE -> ClassName.get(Double.class);
            case STRING -> ClassName.get(String.class);
            case BIG_DECIMAL -> ClassName.get("java.math", "BigDecimal");
            case BYTES -> ArrayTypeName.of(TypeName.BYTE);
            case LOCAL_DATE -> ClassName.get("java.time", "LocalDate");
            case LOCAL_TIME -> ClassName.get("java.time", "LocalTime");
            case LOCAL_DATE_TIME -> ClassName.get("java.time", "LocalDateTime");
            case INSTANT -> ClassName.get("java.time", "Instant");
            case ENUM -> ClassName.bestGuess(enumType);
        };
    }

    /**
     * Expression reading one column: a direct ResultSet getter when null
     * handling is free, a JdbcCodec helper otherwise — the TypeHandler lookup
     * of MyBatis, collapsed at build time (design §02).
     */
    static CodeBlock readExpression(ValueKind kind, String enumType, String resultSetVar,
            CodeBlock column) {
        if (kind == ValueKind.ENUM) {
            return CodeBlock.of("$T.enumValue($L, $L, $T.class)",
                    JDBC_CODEC, resultSetVar, column, ClassName.bestGuess(enumType));
        }
        if (kind.resultSetGetter() != null) {
            return CodeBlock.of("$L.$L($L)", resultSetVar, kind.resultSetGetter(), column);
        }
        return CodeBlock.of("$T.$L($L, $L)", JDBC_CODEC, kind.codecReader(), resultSetVar, column);
    }

    /** Statement binding one value: {@code ps.setLong(1, id)} or a JdbcCodec helper. */
    static CodeBlock writeStatement(ValueKind kind, int index, String accessor) {
        if (kind.statementSetter() != null) {
            return CodeBlock.of("ps.$L($L, $L)", kind.statementSetter(), index, accessor);
        }
        return CodeBlock.of("$T.$L(ps, $L, $L)", JDBC_CODEC, kind.codecWriter(), index, accessor);
    }
}
