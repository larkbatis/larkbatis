package com.example.lbsample;

/**
 * A value type with no JDBC strategy of its own. It is the point of the whole
 * handler mechanism: the type whitelist cannot grow to hold every domain type
 * an application invents, so the application names the code that moves it.
 */
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

    @Override
    public String toString() {
        return cents / 100 + "." + String.format("%02d", Math.abs(cents % 100));
    }
}
