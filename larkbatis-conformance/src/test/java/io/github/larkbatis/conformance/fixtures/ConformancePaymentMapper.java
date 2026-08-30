package io.github.larkbatis.conformance.fixtures;

import io.github.larkbatis.annotations.Mapper;
import java.util.List;

/** One interface, one XML file, two frameworks — as with the squad mapper. */
@Mapper
public interface ConformancePaymentMapper {

    List<Payment> all();

    Payment find(long id);
}
