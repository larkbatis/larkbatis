package com.example.lbsample;

import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Options;
import io.github.larkbatis.annotations.OrderBy;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.runtime.LarkBatisSession;
import io.github.larkbatis.runtime.SqlFragment;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * A cursor, not a list. The caller closes the stream, and
     * closing it closes the ResultSet and statement and releases the
     * Connection: {@code try (Stream<User> rows = mapper.streamAll())}.
     */
    @Select("SELECT id, name, email, created_at FROM users ORDER BY id")
    Stream<User> streamAll();

    /** A scalar stream: column 1, no bean, no reader. */
    @Select("SELECT name FROM users ORDER BY id")
    Stream<String> streamNames();

    /** {@code SELECT *}: the indexes resolve from metadata before the first row. */
    @Select("SELECT * FROM users ORDER BY id")
    Stream<User> streamStarred();

    /** The escape hatch, streaming. */
    default Stream<User> streamRecent(LarkBatisSession s, int limit) {
        return s.queryStream(
                SqlFragment.unsafeRawSql("SELECT id, name, email, created_at FROM users"
                        + " ORDER BY created_at DESC LIMIT " + limit),
                ps -> { },
                UserRow.READER);
    }

    /** The manual escape hatch: hand-assembled SQL, generated reader. */
    default List<User> recent(LarkBatisSession s, int limit) {
        return s.query(
                SqlFragment.unsafeRawSql("SELECT id, name, email, created_at FROM users"
                        + " ORDER BY created_at DESC LIMIT " + limit),
                ps -> { },
                UserRow.READER);
    }
}
