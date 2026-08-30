package io.github.larkbatis.bench;

import io.github.larkbatis.annotations.Select;
import java.util.List;

/** LarkBatis side of the row-read comparison, 4 columns. */
public interface LarkBatisNarrowMapper {

    @Select("SELECT id, name, email, created_at FROM narrow ORDER BY id")
    List<NarrowRow> findAll();

    @Select("SELECT id, name, email, created_at FROM narrow WHERE id = #{id}")
    NarrowRow findById(long id);
}
