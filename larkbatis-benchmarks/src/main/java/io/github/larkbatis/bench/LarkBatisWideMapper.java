package io.github.larkbatis.bench;

import io.github.larkbatis.annotations.Select;
import java.util.List;

/** LarkBatis side of the row-read comparison, 12 columns. */
public interface LarkBatisWideMapper {

    @Select("SELECT id, name, email, created_at, code, status, quantity, ratio, active, score, note, revision FROM wide ORDER BY id")
    List<WideRow> findAll();

    @Select("SELECT id, name, email, created_at, code, status, quantity, ratio, active, score, note, revision FROM wide WHERE id = #{id}")
    WideRow findById(long id);
}
