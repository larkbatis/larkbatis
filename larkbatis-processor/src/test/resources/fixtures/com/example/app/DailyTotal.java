package com.example.app;

import io.github.larkbatis.annotations.LarkBatisRow;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read only by the escape hatch — no statement returns it, so nothing would
 * ask for its reader. {@code @LarkBatisRow} is what asks.
 */
@LarkBatisRow
public class DailyTotal {

    private LocalDate day;
    private BigDecimal revenue;

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }

    public void setRevenue(BigDecimal revenue) {
        this.revenue = revenue;
    }
}
