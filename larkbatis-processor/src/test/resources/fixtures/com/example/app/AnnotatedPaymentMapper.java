package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import io.github.larkbatis.annotations.Select;
import java.util.List;

@Mapper
public interface AnnotatedPaymentMapper {

    @Select("SELECT id, amount FROM payments")
    List<AnnotatedPayment> all();
}
