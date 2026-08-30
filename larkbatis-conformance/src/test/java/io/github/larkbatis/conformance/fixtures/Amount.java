package io.github.larkbatis.conformance.fixtures;

/** A domain type neither framework knows; both reach it only through a handler. */
public final class Amount {

    private final long cents;

    public Amount(long cents) {
        this.cents = cents;
    }

    public long cents() {
        return cents;
    }
}
