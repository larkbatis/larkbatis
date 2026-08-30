package com.example.lbsample;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/** Statements live in {@code src/main/resources/mappers/TeamMapper.xml}. */
@Mapper
public interface TeamMapper {

    /** One team with its members, or null. */
    Team findWithMembers(long id);

    /** Every team with its members, LEFT JOINed so empty teams still appear. */
    List<Team> findAllWithMembers();

    /** One level the other way: a single nested object per parent. */
    List<Team> findAllWithCoach();

    /** A flat result map over a select list the generator cannot parse. */
    List<Team> findAllFlat();

    /**
     * The nested map, but with a select list the generator cannot parse — the
     * positions come from ResultSetMetaData, matched on the declared column
     * names. Must produce exactly what {@link #findAllWithMembers()} does.
     */
    List<Team> findAllWithMembersByName();
}
