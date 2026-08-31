package com.example.app;

import io.github.larkbatis.annotations.Column;

/**
 * A result class for the two column-naming conventions: one property whose
 * name differs from its column only by the underscores, and one that says so
 * with {@code @Column}.
 */
public class Profile {

    private long id;
    private String userName;
    private String zipCode;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getZipCode() {
        return zipCode;
    }

    @Column("zip_code")
    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }
}
