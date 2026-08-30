package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Select;

@Mapper
public interface LombokUserMapper {

    @Select("SELECT id, name FROM users WHERE id = #{id}")
    LombokUser findById(long id);
}
