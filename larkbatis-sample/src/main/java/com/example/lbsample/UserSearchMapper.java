package com.example.lbsample;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;
import java.util.stream.Stream;

/** XML-backed mapper: statements live in mappers/UserSearchMapper.xml. */
@Mapper
public interface UserSearchMapper {

    List<User> search(UserSearch q);

    int rename(UserSearch q);

    /**
     * A cursor whose binding reads a property off the parameter — so a null
     * argument throws before the stream exists, which is the shape the
     * release path has to survive.
     */
    Stream<User> streamByName(UserSearch q);
}
