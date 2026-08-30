package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface BadQueryMapper {

    List<User> search(UserQuery q);
}
