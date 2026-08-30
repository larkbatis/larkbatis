package com.example.lbsample;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.PadPow2;
import io.github.larkbatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** XML-backed {@code <foreach>} mapper: statements in mappers/UserBatchMapper.xml. */
@Mapper
public interface UserBatchMapper {

    List<User> findByIds(List<Long> ids);

    /** Same statement shape, with the placeholder count padded. */
    @PadPow2
    List<User> findByIdsPadded(List<Long> ids);

    List<User> findByNames(String[] names);

    /** {@code index} is the position; used here to keep the input order. */
    List<User> findByIdsOrdered(List<Long> ids);

    /** Map iteration: {@code index} is the key, {@code item} the value (issue #709). */
    List<User> findByColumnValues(Map<String, String> filters);

    /** Multi-row VALUES built by the loop, not addBatch(). */
    int insertAll(List<User> users);

    int deleteByIds(@Param("ids") List<Long> ids, @Param("keepName") String keepName);

    /** {@code ${}} inside the body: the loop must walk the elements to splice them. */
    List<User> countByColumns(List<io.github.larkbatis.runtime.SqlFragment> columns);

    /** Two sibling loops that both name their index {@code position}. */
    List<User> siblingIndexNames(@Param("ids") List<Long> ids, @Param("names") List<String> names);

    /** Padding where the single bind is a property of the element, not the element. */
    @PadPow2
    List<User> findByEmails(List<User> probes);

    /** Nested loops: the outer item is the inner loop's collection. */
    List<User> findByIdGroups(List<List<Long>> groups);
}
