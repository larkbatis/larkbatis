package com.example.app;

import io.github.larkbatis.annotations.Handler;
import io.github.larkbatis.annotations.Select;

public interface MethodHandlerMapper {

    /** @Handler on a mapper method names nothing the generator can honour. */
    @Handler(MoneyHandler.class)
    @Select("SELECT balance FROM accounts WHERE id = #{id}")
    Money balanceOf(long id);
}
