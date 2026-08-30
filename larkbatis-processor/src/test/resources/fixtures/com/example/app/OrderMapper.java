package com.example.app;

import io.github.larkbatis.annotations.Delete;
import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Options;
import io.github.larkbatis.annotations.OrderBy;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.annotations.Update;
import io.github.larkbatis.runtime.SqlFragment;
import java.util.List;

/** Exercises every annotation-path statement shape in one mapper. */
public interface OrderMapper {

    @Select("SELECT id, status, total, placed_at FROM orders WHERE status = #{status}")
    List<Order> byStatus(Status status);

    @Select("SELECT * FROM orders WHERE id = #{id}")
    Order byId(long id);

    @Select("SELECT total, id FROM orders WHERE id = #{id}")
    Order totalAndId(long id);

    @Select("SELECT COUNT(*) cnt FROM orders WHERE status = #{status}")
    long countByStatus(Status status);

    @Select("SELECT id, status, total, placed_at FROM orders ORDER BY ${sort}")
    List<Order> sorted(@OrderBy(allowed = {"id", "total"}) String sort);

    @Select("SELECT id, status, total, placed_at FROM orders ORDER BY ${sort}")
    List<Order> sortedBy(SqlFragment sort);

    @Update("UPDATE orders SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") Status status);

    @Insert("INSERT INTO orders (status, total, placed_at) VALUES (#{status}, #{total}, #{placedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Order o);

    @Insert("INSERT INTO orders (status, total, placed_at) VALUES (#{status}, #{total}, #{placedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertAll(List<Order> orders);

    @Delete("DELETE FROM orders WHERE id = #{id}")
    void delete(long id);

    /** A cursor the caller closes, not a List. */
    @Select("SELECT id, status, total, placed_at FROM orders WHERE status = #{status}")
    java.util.stream.Stream<Order> streamByStatus(Status status);
}
