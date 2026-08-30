package io.github.larkbatis.conformance.fixtures;

import java.util.List;

/**
 * One interface, two frameworks: every statement
 * carries both the MyBatis and the LarkBatis annotation, referencing the
 * same SQL constant so the two sides can never drift apart. MyBatis ignores
 * LarkBatis annotations and vice versa.
 */
public interface ConformanceUserMapper {

    String FIND_BY_ID =
            "SELECT id, name, age, email, created_at FROM users WHERE id = #{id}";

    String SEARCH =
            "SELECT id, name, age, email, created_at FROM users"
                    + " WHERE name LIKE #{pattern} AND age >= #{minAge} ORDER BY id";

    String INSERT =
            "INSERT INTO users (name, age, email, created_at)"
                    + " VALUES (#{name}, #{age}, #{email}, #{createdAt})";

    String UPDATE_EMAIL =
            "UPDATE users SET email = #{email} WHERE id = #{id}";

    String DELETE_BY_AGE =
            "DELETE FROM users WHERE age < #{minAge}";

    @org.apache.ibatis.annotations.Select(FIND_BY_ID)
    @io.github.larkbatis.annotations.Select(FIND_BY_ID)
    User findById(long id);

    @org.apache.ibatis.annotations.Select(SEARCH)
    @io.github.larkbatis.annotations.Select(SEARCH)
    List<User> search(
            @org.apache.ibatis.annotations.Param("pattern")
            @io.github.larkbatis.annotations.Param("pattern") String pattern,
            @org.apache.ibatis.annotations.Param("minAge")
            @io.github.larkbatis.annotations.Param("minAge") int minAge);

    @org.apache.ibatis.annotations.Insert(INSERT)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @io.github.larkbatis.annotations.Insert(INSERT)
    @io.github.larkbatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User u);

    @org.apache.ibatis.annotations.Update(UPDATE_EMAIL)
    @io.github.larkbatis.annotations.Update(UPDATE_EMAIL)
    int updateEmail(
            @org.apache.ibatis.annotations.Param("id")
            @io.github.larkbatis.annotations.Param("id") long id,
            @org.apache.ibatis.annotations.Param("email")
            @io.github.larkbatis.annotations.Param("email") String email);

    @org.apache.ibatis.annotations.Delete(DELETE_BY_AGE)
    @io.github.larkbatis.annotations.Delete(DELETE_BY_AGE)
    int deleteByAge(
            @org.apache.ibatis.annotations.Param("minAge")
            @io.github.larkbatis.annotations.Param("minAge") int minAge);
}
