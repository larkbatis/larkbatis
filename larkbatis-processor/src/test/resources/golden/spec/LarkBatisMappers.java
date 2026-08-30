package com.example.app;

import io.github.larkbatis.runtime.LarkBatisSession;
import javax.annotation.processing.Generated;

/**
 * The closed set of LarkBatis mappers, known at compile time.
 * Generated — do not edit.
 */
@Generated("io.github.larkbatis.processor.LarkBatisProcessor")
public final class LarkBatisMappers {
    private LarkBatisMappers() {
    }

    public static UserMapper userMapper(LarkBatisSession s) {
        return new UserMapper$$Impl(s);
    }
}
