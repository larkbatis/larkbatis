package com.example.app;

import io.github.larkbatis.annotations.Select;
import java.util.List;

public interface ContactMapper {

    /** Select list in declaration order: the canonical positional reader. */
    @Select("SELECT contact_id, usr_email, mobile FROM contacts WHERE contact_id = #{id}")
    Contact find(long id);

    /** Unparseable select list: the name-based reader, matching on @Column names. */
    @Select("SELECT * FROM contacts")
    List<Contact> all();
}
