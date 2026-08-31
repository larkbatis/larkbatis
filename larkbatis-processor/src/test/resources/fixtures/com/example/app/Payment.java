package com.example.app;

/**
 * A result class with a {@code Money} property and no {@code @Handler} on it:
 * unreadable unless a build-wide handler is registered for the type.
 */
public class Payment {

    private long id;
    private Money amount;

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
}
