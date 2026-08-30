package com.example.app;

import io.github.larkbatis.annotations.Select;

public interface WrongHandlerMapper {

    @Select("SELECT id, balance FROM accounts WHERE id = #{id}")
    WrongHandlerAccount find(long id);
}
