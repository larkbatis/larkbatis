package com.example.lbsample;

import io.github.larkbatis.annotations.LarkBatisRow;

/**
 * The shape of an ad-hoc aggregate: no statement returns it, so nothing would
 * ask for its reader. {@code @LarkBatisRow} asks, and the escape hatch then
 * has a generated {@code DomainCountRow.READER} to pass — the reason
 * {@code s.query(...)} takes a {@code RowReader<T>} and not a
 * {@code Class<T>}, which would need reflection to make sense of.
 */
@LarkBatisRow
public class DomainCount {

    private String domain;
    private long total;

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
