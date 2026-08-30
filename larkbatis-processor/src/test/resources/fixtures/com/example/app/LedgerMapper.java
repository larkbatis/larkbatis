package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Param;

@Mapper
public interface LedgerMapper {

    Entry find(long id);

    int insert(@Param("id") long id, @Param("amount") Money amount, @Param("note") String note);
}
