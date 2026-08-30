package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.PadPow2;
import java.util.List;

/** XML-backed mapper: the SQL lives in UserQueryMapper.xml. */
@Mapper
public interface UserQueryMapper {

    List<User> search(UserQuery q);

    List<User> byStatus(UserQuery q);

    int rename(UserQuery q);

    List<User> all();

    /** The IN-list landmark. */
    List<User> findByIds(List<Long> ids);

    /** Padded to the next power of two, repeating the last element. */
    @PadPow2
    List<User> findByIdsPadded(List<Long> ids);

    /** A <foreach> under an <if>, and index bound alongside item. */
    List<User> searchInIds(UserQuery q);
}
