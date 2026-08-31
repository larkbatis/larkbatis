package com.example.app;

import io.github.larkbatis.annotations.Handler;

/** The same Money property, this time naming its own handler. */
public class AnnotatedPayment {

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

    @Handler(AltMoneyHandler.class)
    public void setAmount(Money amount) {
        this.amount = amount;
    }
}
