package com.example.lbsample;

import io.github.lightbatis.annotations.Insert;
import io.github.lightbatis.annotations.Options;
import io.github.lightbatis.annotations.OrderBy;
import io.github.lightbatis.annotations.Param;
import io.github.lightbatis.annotations.Select;
import io.github.lightbatis.runtime.LightBatisSession;
import io.github.lightbatis.runtime.SqlFragment;
import java.util.List;

public interface UserMapper {

    @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id}")
    User findById(long id);

    @Select("SELECT id, name, email, created_at FROM users ORDER BY ${sort}")
    List<User> all(@OrderBy(allowed = {"id", "name", "created_at"}) String sort);

    @Select("SELECT COUNT(*) c FROM users WHERE name LIKE #{pattern}")
    long countByName(@Param("pattern") String pattern);

    @Insert("INSERT INTO users (name, email, created_at) VALUES (#{name}, #{email}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User u);

    @Insert("INSERT INTO users (name, email, created_at) VALUES (#{name}, #{email}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAll(List<User> users);

    /** The manual escape hatch (design §09): hand-assembled SQL, generated reader. */
    default List<User> recent(LightBatisSession s, int limit) {
        return s.query(
                SqlFragment.unsafeRawSql("SELECT id, name, email, created_at FROM users"
                        + " ORDER BY created_at DESC LIMIT " + limit),
                ps -> { },
                UserRow.READER);
    }
}
