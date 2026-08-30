package io.github.larkbatis.conformance.fixtures;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/**
 * One interface, one XML file, two frameworks. MyBatis finds
 * {@code ConformanceSquadMapper.xml} next to this class on the classpath;
 * LarkBatis reads the same file at compile time through
 * {@code -Alarkbatis.mapperDir}. They cannot drift apart silently.
 */
@Mapper
public interface ConformanceSquadMapper {

    List<Squad> allWithPlayers();

    List<Squad> allWithCaptain();

    Squad oneWithPlayers(long id);
}
