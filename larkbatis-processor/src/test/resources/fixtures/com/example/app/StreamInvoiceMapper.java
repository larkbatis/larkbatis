package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import java.util.stream.Stream;

/** A Stream return over a nested result map — rejected at build time. */
@Mapper
public interface StreamInvoiceMapper {

    Stream<Invoice> streamWithLines();
}
