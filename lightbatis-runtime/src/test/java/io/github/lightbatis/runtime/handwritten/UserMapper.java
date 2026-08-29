package io.github.lightbatis.runtime.handwritten;

import io.github.lightbatis.annotations.Insert;
import io.github.lightbatis.annotations.Options;
import io.github.lightbatis.annotations.Select;

/**
 * The one mapper of the hand-written emitter spec (M1 task 1): one SELECT
 * with {@code #{}} and one INSERT with generated keys (design §04, §07).
 */
public interface UserMapper {

    @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id}")
    User findById(long id);

    @Insert("INSERT INTO users (name, email, created_at) VALUES (#{name}, #{email}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User u);
}
