package com.example.lbsample;

import io.github.larkbatis.annotations.Handler;
import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import java.util.List;

@Mapper
public interface WalletMapper {

    @Select("SELECT id, balance FROM wallet WHERE id = #{id}")
    Wallet find(long id);

    /** The bind reads the handler off the property it binds. */
    @Insert("INSERT INTO wallet (id, balance) VALUES (#{w.id}, #{w.balance})")
    int insert(@Param("w") Wallet w);

    /** The parameter's own @Handler, on a type the whitelist does not know. */
    @Select("SELECT id FROM wallet WHERE balance >= #{floor} ORDER BY id")
    List<Long> atLeast(@Handler(MoneyHandler.class) Money floor);
}
