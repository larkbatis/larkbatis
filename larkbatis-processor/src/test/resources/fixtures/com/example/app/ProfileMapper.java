package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Select;
import java.util.List;

@Mapper
public interface ProfileMapper {

    @Select("SELECT id, user_name, zip_code FROM profiles")
    List<Profile> all();
}
