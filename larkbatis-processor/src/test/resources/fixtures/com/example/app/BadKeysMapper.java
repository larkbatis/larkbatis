package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/** Generated keys asked for on a multi-row {@code <foreach>} insert. */
@Mapper
public interface BadKeysMapper {

    int insertAll(List<User> users);
}
