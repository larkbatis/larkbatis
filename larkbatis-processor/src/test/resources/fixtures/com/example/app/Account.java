package com.example.app;

import io.github.larkbatis.annotations.Handler;

/**
 * {@code @Handler} on two of the three sites it may sit on, over a type the
 * whitelist rejects and a type it accepts.
 */
public class Account {

    private long id;

    @Handler(MoneyHandler.class)
    private Money balance;

    private String owner;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Money getBalance() {
        return balance;
    }

    public void setBalance(Money balance) {
        this.balance = balance;
    }

    public String getOwner() {
        return owner;
    }

    @Handler(UpperHandler.class)
    public void setOwner(String owner) {
        this.owner = owner;
    }
}
