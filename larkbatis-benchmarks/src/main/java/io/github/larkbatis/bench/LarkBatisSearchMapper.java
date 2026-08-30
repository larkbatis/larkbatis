package io.github.larkbatis.bench;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/** LarkBatis side of the dynamic-SQL comparison; statements in mappers/SearchMapper.xml. */
@Mapper
public interface LarkBatisSearchMapper {

    List<NarrowRow> search(SearchQuery q);
}
