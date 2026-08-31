package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.annotations.Insert;
import java.util.List;

/** Reads a Money column and binds a Money parameter, neither one annotated. */
@Mapper
public interface PaymentMapper {

    @Select("SELECT id, amount FROM payments WHERE amount = #{amount}")
    List<Payment> byAmount(@Param("amount") Money amount);

    @Insert("INSERT INTO payments (id, amount) VALUES (#{p.id}, #{p.amount})")
    int insert(@Param("p") Payment payment);
}
