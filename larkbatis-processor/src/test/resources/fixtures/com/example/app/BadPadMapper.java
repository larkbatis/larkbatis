package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.PadPow2;
import java.util.List;

/** Padding asked for where repeating the last element would be visible. */
@Mapper
@PadPow2
public interface BadPadMapper {

    int insertAll(List<User> users);
}
