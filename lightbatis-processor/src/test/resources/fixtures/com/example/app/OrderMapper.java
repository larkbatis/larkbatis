package com.example.app;

import io.github.lightbatis.annotations.Delete;
import io.github.lightbatis.annotations.Insert;
import io.github.lightbatis.annotations.Options;
import io.github.lightbatis.annotations.OrderBy;
import io.github.lightbatis.annotations.Param;
import io.github.lightbatis.annotations.Select;
import io.github.lightbatis.annotations.Update;
import io.github.lightbatis.runtime.SqlFragment;
import java.util.List;

/** Exercises every M1 statement shape in one mapper. */
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
}
