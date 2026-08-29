package com.example.app;

import io.github.lightbatis.runtime.LightBatisSession;
import javax.annotation.processing.Generated;

/**
 * The closed set of LightBatis mappers, known at compile time (design §08 group 3).
 * Generated — do not edit.
 */
@Generated("io.github.lightbatis.processor.LightBatisProcessor")
public final class LightBatisMappers {
    private LightBatisMappers() {
    }

    public static UserMapper userMapper(LightBatisSession s) {
        return new UserMapper$$Impl(s);
    }
}
