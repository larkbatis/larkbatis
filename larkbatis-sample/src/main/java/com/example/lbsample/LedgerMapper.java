package com.example.lbsample;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Param;
import java.util.List;

/** XML-backed: every handler this mapper uses is declared in LedgerMapper.xml. */
@Mapper
public interface LedgerMapper {

    Entry find(long id);

    List<Entry> all();

    int insert(@Param("id") long id, @Param("amount") Money amount, @Param("note") String note);
}
