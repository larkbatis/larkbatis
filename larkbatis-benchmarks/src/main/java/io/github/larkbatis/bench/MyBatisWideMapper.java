package io.github.larkbatis.bench;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/** MyBatis side of the row-read comparison, 12 columns — the same SQL. */
public interface MyBatisWideMapper {

    @Select("SELECT id, name, email, created_at, code, status, quantity, ratio, active, score, note, revision FROM wide ORDER BY id")
    List<WideRow> findAll();

    @Select("SELECT id, name, email, created_at, code, status, quantity, ratio, active, score, note, revision FROM wide WHERE id = #{id}")
    WideRow findById(long id);
}
