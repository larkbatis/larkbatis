package com.example.app;

/** A value type the built-in codec has no strategy for; only a handler moves it. */
public final class Money {

    private final long cents;

    public Money(long cents) {
        this.cents = cents;
    }

    public long cents() {
        return cents;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && other.cents == cents;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(cents);
    }
}
