package io.github.larkbatis.conformance;

import java.lang.reflect.Field;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/** Readable names for {@link Types} constants in recorded setNull calls. */
final class JdbcTypeNames {

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        for (Field field : Types.class.getFields()) {
            try {
                NAMES.put((Integer) field.get(null), field.getName());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private JdbcTypeNames() {
    }

    static String of(int typeCode) {
        return NAMES.getOrDefault(typeCode, String.valueOf(typeCode));
    }
}
