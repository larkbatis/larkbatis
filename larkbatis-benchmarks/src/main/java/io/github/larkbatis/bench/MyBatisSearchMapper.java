package io.github.larkbatis.bench;

import java.util.List;

/** MyBatis side of the dynamic-SQL comparison; statements in mybatis/SearchMapper.xml. */
public interface MyBatisSearchMapper {

    List<NarrowRow> search(SearchQuery q);
}
