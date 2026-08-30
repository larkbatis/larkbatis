package com.example.lbsample;

import io.github.larkbatis.annotations.Column;

/**
 * A legacy table whose column names the snake_case convention cannot reach:
 * {@code contact_id} would want a property called {@code contactId}, and
 * {@code mobile} has no relation to {@code phone} at all. {@code @Column}
 * names the column on the property instead of bending the property to the
 * column — the alternative is aliasing every column in every select list.
 *
 * <p>All three sites are used on purpose: the annotation targets FIELD and
 * METHOD both, and this class is what proves all three are read.
 */
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
