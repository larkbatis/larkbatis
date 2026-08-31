package io.github.larkbatis.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import io.github.larkbatis.processor.ir.ColumnNaming;
import io.github.larkbatis.processor.ir.ValueKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared emit vocabulary: runtime class names (referenced by name — the
 * processor has no compile dependency on the runtime), type-name
 * reconstruction from IR strings, and the read/write code shapes chosen by
 * {@link ValueKind}.
 */
public final class EmitSupport {

    static final String RUNTIME_PACKAGE = "io.github.larkbatis.runtime";

    static final ClassName LIGHT_BATIS_SESSION = ClassName.get(RUNTIME_PACKAGE, "LarkBatisSession");
    static final ClassName JDBC_CODEC = ClassName.get(RUNTIME_PACKAGE, "JdbcCodec");
    static final ClassName LIGHT_BATIS_SQL = ClassName.get(RUNTIME_PACKAGE, "LarkBatisSql");
    static final ClassName LIGHT_BATIS_EXCEPTION = ClassName.get(RUNTIME_PACKAGE, "LarkBatisException");
    static final ClassName REJECTED_EXCEPTION = ClassName.get(RUNTIME_PACKAGE, "LarkBatisRejectedException");
    static final ClassName KEY_COUNT_MISMATCH = ClassName.get(RUNTIME_PACKAGE, "LarkBatisKeyCountMismatchException");
    static final ClassName EMPTY_FOREACH = ClassName.get(RUNTIME_PACKAGE, "LarkBatisEmptyForeachException");
    static final ClassName NO_KEY = ClassName.get(RUNTIME_PACKAGE, "LarkBatisNoKeyException");
    static final ClassName ROW_READER = ClassName.get(RUNTIME_PACKAGE, "RowReader");

    static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    static final ClassName PREPARED_STATEMENT = ClassName.get("java.sql", "PreparedStatement");
    static final ClassName RESULT_SET = ClassName.get("java.sql", "ResultSet");
    static final ClassName RESULT_SET_META_DATA = ClassName.get("java.sql", "ResultSetMetaData");
    static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    static final ClassName SQL_STATEMENT = ClassName.get("java.sql", "Statement");
    static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    static final ClassName LIST = ClassName.get("java.util", "List");
    static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    static final ClassName LOCALE = ClassName.get("java.util", "Locale");
    static final ClassName STREAM = ClassName.get("java.util.stream", "Stream");

    private EmitSupport() {
    }

    static AnnotationSpec generatedAnnotation() {
        return AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                .addMember("value", "$S", "io.github.larkbatis.processor.LarkBatisProcessor")
                .build();
    }

    /**
     * The value a local of this type is initialized with before a loop
     * assigns it for real — Java's definite-assignment analysis cannot see
     * that a {@code <foreach>} body always runs at least once.
     */
    static String defaultValue(String fqn) {
        return switch (fqn.trim()) {
            case "boolean" -> "false";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0f";
            case "double" -> "0d";
            case "char" -> "'\\0'";
            default -> "null";
        };
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

    /**
     * The literal a local of this {@link ValueKind} starts at, before a loop
     * assigns it for real. Only the grouping loop of a nested result map needs
     * this: its key locals are declared outside the loop, and Java's
     * definite-assignment analysis cannot see that the first iteration always
     * writes them.
     */
    /**
     * The expression a generated column resolver switches on: the label at
     * position {@code indexVar}, normalized as far as the chosen naming
     * convention normalizes it. The {@code case} labels are built from the same
     * convention, so the two cannot drift apart.
     */
    static CodeBlock columnLabelKey(ColumnNaming naming, String indexVar) {
        return naming.ignoresUnderscores()
                ? CodeBlock.of("md.getColumnLabel($L).replace($S, $S).toLowerCase($T.ROOT)",
                        indexVar, "_", "", LOCALE)
                : CodeBlock.of("md.getColumnLabel($L).toLowerCase($T.ROOT)", indexVar, LOCALE);
    }

    static CodeBlock defaultLiteral(ValueKind kind) {
        return switch (kind) {
            case PRIM_BOOLEAN -> CodeBlock.of("false");
            case PRIM_BYTE, PRIM_SHORT, PRIM_INT -> CodeBlock.of("0");
            case PRIM_LONG -> CodeBlock.of("0L");
            case PRIM_FLOAT -> CodeBlock.of("0f");
            case PRIM_DOUBLE -> CodeBlock.of("0d");
            default -> CodeBlock.of("null");
        };
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
            case PRIM_CHAR -> TypeName.CHAR;
            case BOX_CHARACTER -> ClassName.get(Character.class);
            case STRING -> ClassName.get(String.class);
            case BIG_DECIMAL -> ClassName.get("java.math", "BigDecimal");
            case BIG_INTEGER -> ClassName.get("java.math", "BigInteger");
            case BYTES -> ArrayTypeName.of(TypeName.BYTE);
            case LOCAL_DATE -> ClassName.get("java.time", "LocalDate");
            case LOCAL_TIME -> ClassName.get("java.time", "LocalTime");
            case LOCAL_DATE_TIME -> ClassName.get("java.time", "LocalDateTime");
            case INSTANT -> ClassName.get("java.time", "Instant");
            case OFFSET_DATE_TIME -> ClassName.get("java.time", "OffsetDateTime");
            case OFFSET_TIME -> ClassName.get("java.time", "OffsetTime");
            case ZONED_DATE_TIME -> ClassName.get("java.time", "ZonedDateTime");
            case SQL_DATE -> ClassName.get("java.sql", "Date");
            case SQL_TIME -> ClassName.get("java.sql", "Time");
            case SQL_TIMESTAMP -> ClassName.get("java.sql", "Timestamp");
            case UTIL_DATE -> ClassName.get("java.util", "Date");
            case ENUM -> ClassName.bestGuess(enumType);
        };
    }

    /**
     * Expression reading one column: a direct ResultSet getter when null
     * handling is free, a JdbcCodec helper otherwise — the TypeHandler lookup
     * of MyBatis, collapsed at build time.
     */
    static CodeBlock readExpression(ValueKind kind, String enumType, String resultSetVar,
            CodeBlock column) {
        return readExpression(kind, enumType, null, resultSetVar, column);
    }

    /**
     * Same, through a handler when one was named. {@code handlerField} is the
     * {@link HandlerFields}-allocated field on the enclosing generated type;
     * when it is set, {@code kind} is the strategy the type would have had and
     * is deliberately not consulted — a handler that only agreed with the
     * built-in codec would not be worth naming.
     */
    static CodeBlock readExpression(ValueKind kind, String enumType, String handlerField,
            String resultSetVar, CodeBlock column) {
        if (handlerField != null) {
            return CodeBlock.of("$L.read($L, $L)", handlerField, resultSetVar, column);
        }
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
        return writeStatement(kind, CodeBlock.of("$L", index), accessor, null);
    }

    /** Same, through a handler when one was named. */
    static CodeBlock writeStatement(ValueKind kind, int index, String accessor,
            String handlerField) {
        return writeStatement(kind, CodeBlock.of("$L", index), accessor, handlerField);
    }

    /** Same, with a computed index — dynamic bodies bind at {@code i++}. */
    static CodeBlock writeStatement(ValueKind kind, CodeBlock index, String accessor) {
        return writeStatement(kind, index, accessor, null);
    }

    /** Same again, with both a computed index and a handler. */
    static CodeBlock writeStatement(ValueKind kind, CodeBlock index, String accessor,
            String handlerField) {
        if (handlerField != null) {
            return CodeBlock.of("$L.write(ps, $L, $L)", handlerField, index, accessor);
        }
        if (kind.statementSetter() != null) {
            return CodeBlock.of("ps.$L($L, $L)", kind.statementSetter(), index, accessor);
        }
        return CodeBlock.of("$T.$L(ps, $L, $L)", JDBC_CODEC, kind.codecWriter(), index, accessor);
    }
}
