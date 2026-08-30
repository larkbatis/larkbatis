package com.example.lbsample;

/**
 * Carries no LarkBatis annotation at all: the handler is named in the mapper
 * XML, which is the state a migrated MyBatis mapper arrives in.
 */
public class Entry {

    private long id;
    private Money amount;
    private String note;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
