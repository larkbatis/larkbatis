package com.example.app;

/**
 * No {@code @Handler} anywhere: the handlers come from the mapper XML, which is
 * the state a mapper arrives in when it is migrated rather than rewritten.
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
