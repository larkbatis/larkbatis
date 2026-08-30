package com.example.app;

import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.runtime.LarkBatisSession;
import io.github.larkbatis.runtime.SqlFragment;
import java.util.List;

/** A mapper whose fast path and hand path return different classes. */
public interface ReportMapper {

    @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id}")
    User findById(long id);

    /** The escape hatch: SQL assembled here, reader still generated. */
    default List<DailyTotal> totals(LarkBatisSession s, String table) {
        return s.query(
                SqlFragment.unsafeRawSql(
                        "SELECT day, revenue FROM " + SqlFragment.identifier(table).text()),
                ps -> { },
                DailyTotalRow.READER);
    }
}
