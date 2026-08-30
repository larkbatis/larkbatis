package com.example.app;

import io.github.larkbatis.annotations.Select;

public interface NeedsArgMapper {

    @Select("SELECT id, balance FROM accounts WHERE id = #{id}")
    NeedsArgAccount find(long id);
}
