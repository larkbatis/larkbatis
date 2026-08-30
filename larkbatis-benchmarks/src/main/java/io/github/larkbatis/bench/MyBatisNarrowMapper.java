package io.github.larkbatis.bench;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/** MyBatis side of the row-read comparison, 4 columns — the same SQL. */
public interface MyBatisNarrowMapper {

    @Select("SELECT id, name, email, created_at FROM narrow ORDER BY id")
    List<NarrowRow> findAll();

    @Select("SELECT id, name, email, created_at FROM narrow WHERE id = #{id}")
    NarrowRow findById(long id);
}
