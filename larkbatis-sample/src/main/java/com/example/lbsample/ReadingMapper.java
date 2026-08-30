package com.example.lbsample;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Options;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.annotations.Insert;
import java.util.Date;
import java.util.List;

/**
 * The widened type whitelist, on both sides of the wire: every type is bound
 * as a parameter somewhere here and read back as a property of {@link Reading}.
 * A type that only ever appeared in a result class would leave the binding
 * half untested, and the two halves are separate code in the emitter.
 */
@Mapper
public interface ReadingMapper {

    @Insert("INSERT INTO readings"
            + " (taken_at, recorded_at, due_on, alarm_at, observed_at, counter, grade, flag)"
            + " VALUES (#{takenAt}, #{recordedAt}, #{dueOn}, #{alarmAt}, #{observedAt},"
            + " #{counter}, #{grade}, #{flag})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Reading reading);

    @Select("SELECT id, taken_at, recorded_at, due_on, alarm_at, observed_at, counter,"
            + " grade, flag FROM readings WHERE id = #{id}")
    Reading find(long id);

    /** {@code java.util.Date} as a bound parameter, not only as a column read. */
    @Select("SELECT id, taken_at, recorded_at, due_on, alarm_at, observed_at, counter,"
            + " grade, flag FROM readings WHERE taken_at < #{cutoff} ORDER BY id")
    List<Reading> takenBefore(Date cutoff);

    /** A primitive char and a boxed Character, bound side by side. */
    @Select("SELECT id, taken_at, recorded_at, due_on, alarm_at, observed_at, counter,"
            + " grade, flag FROM readings WHERE grade = #{grade} ORDER BY id")
    List<Reading> graded(char grade);

    @Select("SELECT count(*) FROM readings WHERE counter >= #{floor}")
    long atLeast(@Param("floor") java.math.BigInteger floor);
}
