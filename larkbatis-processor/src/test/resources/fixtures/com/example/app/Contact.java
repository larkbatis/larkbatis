package com.example.app;

import io.github.larkbatis.annotations.Column;

/** One result class carrying {@code @Column} on all three sites it may sit on. */
public class Contact {

    @Column("contact_id")
    private long id;
    private String email;
    private String phone;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    @Column("usr_email")
    public void setEmail(String email) {
        this.email = email;
    }

    @Column("mobile")
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
