package com.example.app;

import io.github.larkbatis.annotations.Handler;

public class WrongHandlerAccount {

    private long id;

    @Handler(WrongTypeHandler.class)
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
