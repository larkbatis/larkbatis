package io.github.larkbatis.conformance.fixtures;

import java.util.List;
import java.util.Map;

/**
 * One XML file, two frameworks: the statements live in
 * ConformanceQueryMapper.xml on the test classpath, which MyBatis loads as
 * the mapper resource next to this interface and LarkBatis reads via
 * {@code -Alarkbatis.mapperDir} at compile time. The dynamic-tag folding
 * answers to this suite.
 */
@io.github.larkbatis.annotations.Mapper
public interface ConformanceQueryMapper {

    List<User> search(UserFilter f);

    List<User> byStatus(UserFilter f);

    int updateUser(UserFilter f);

    List<User> listAll();

    // --- <foreach> ------------------------------------------------

    /** Sole collection parameter, addressed by the MyBatis alias "list". */
    List<User> findByIds(List<Long> ids);

    /** The collection as a property of the parameter bean. */
    List<User> searchInIds(UserFilter f);

    /** A <foreach> under an <if>: the guard decides whether the loop runs at all. */
    List<User> searchOptionalIds(UserFilter f);

    /** index bound alongside item, and a second loop over the same collection. */
    List<User> findByIdsOrdered(List<Long> ids);

    /** Map iteration: index is the key, item the value (MyBatis issue #709). */
    List<User> findByColumnValues(
            @org.apache.ibatis.annotations.Param("filters")
            @io.github.larkbatis.annotations.Param("filters") Map<String, String> filters);

    /** Multi-row VALUES written by the loop. */
    int insertAll(
            @org.apache.ibatis.annotations.Param("users")
            @io.github.larkbatis.annotations.Param("users") List<User> users);

    /** The loop carries the &lt;where&gt;'s "AND " in its open attribute. */
    List<User> foreachOpensTheWhere(List<Long> ids);

    /** The statement itself starts with the loop, which has no open. */
    List<Long> unionOfIds(List<Long> ids);

    /** The loop lives in a &lt;when&gt; branch. */
    List<User> chooseWithForeach(UserFilter f);

    /** The loop is the whole content of a &lt;where&gt;. */
    List<User> searchWhereForeachOnly(List<Long> ids);

    /** Two loops with nothing between them. */
    List<User> adjacentForeach(
            @org.apache.ibatis.annotations.Param("ids")
            @io.github.larkbatis.annotations.Param("ids") List<Long> ids,
            @org.apache.ibatis.annotations.Param("names")
            @io.github.larkbatis.annotations.Param("names") List<String> names);

    /** A separator that is only whitespace. */
    List<User> spaceSeparator(List<Long> ids);

    /** Nested loops: the outer item is the inner loop's collection. */
    List<User> findByIdGroups(List<List<Long>> groups);

    /** A foreach next to an ordinary bind. */
    int deleteByIds(
            @org.apache.ibatis.annotations.Param("ids")
            @io.github.larkbatis.annotations.Param("ids") List<Long> ids,
            @org.apache.ibatis.annotations.Param("keepName")
            @io.github.larkbatis.annotations.Param("keepName") String keepName);
}
