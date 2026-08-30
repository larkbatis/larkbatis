package com.example.app;

import java.time.LocalDate;
import java.util.List;

/**
 * The nested-mapping shape: two properties that are not columns and never
 * were — the row reader skips them, and {@code <collection>}/
 * {@code <association>} fill them from the join.
 */
public class Invoice {

    private long id;
    private String number;
    private LocalDate issued;
    private List<InvoiceLine> lines;
    private Customer customer;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getIssued() {
        return issued;
    }

    public void setIssued(LocalDate issued) {
        this.issued = issued;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    public void setLines(List<InvoiceLine> lines) {
        this.lines = lines;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }
}
