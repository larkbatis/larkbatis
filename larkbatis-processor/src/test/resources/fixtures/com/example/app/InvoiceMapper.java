package com.example.app;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/** Every statement is XML-backed; the interface only fixes the types. */
@Mapper
public interface InvoiceMapper {

    List<Invoice> findAllWithLines();

    Invoice findWithLines(long id);

    List<Invoice> findAllWithCustomer();

    List<Invoice> findAllFlat();
}
