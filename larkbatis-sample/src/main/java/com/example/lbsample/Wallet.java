package com.example.lbsample;

import io.github.larkbatis.annotations.Handler;

/** The other declaration site: the handler named on the property itself. */
public class Wallet {

    private long id;

    @Handler(MoneyHandler.class)
    private Money balance;

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
}
