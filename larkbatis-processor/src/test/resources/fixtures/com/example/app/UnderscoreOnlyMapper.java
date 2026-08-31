package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Select;
import java.util.List;

/** Every column spelled with underscores, every property without. */
@Mapper
public interface UnderscoreOnlyMapper {

    @Select("SELECT _id, _name, _email, created_at FROM users")
    List<User> all();
}
