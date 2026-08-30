package com.example.lbsample;

import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Options;
import io.github.larkbatis.annotations.Select;
import io.github.larkbatis.runtime.LarkBatisSession;
import io.github.larkbatis.runtime.SqlFragment;
import java.util.List;

/** Legacy column names on the way in and on the way out, plus an ad-hoc aggregate. */
public interface ContactMapper {

    @Insert("INSERT INTO contacts (usr_email, mobile) VALUES (#{email}, #{phone})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "contact_id")
    int insert(Contact contact);

    /** Select list in declaration order: the canonical positional reader. */
    @Select("SELECT contact_id, usr_email, mobile FROM contacts WHERE contact_id = #{id}")
    Contact findById(long id);

    /** An unparseable select list: the name-based reader, matching the @Column names. */
    @Select("SELECT * FROM contacts ORDER BY contact_id")
    List<Contact> all();

    /**
     * The escape hatch, reading into a class no statement returns —
     * {@code DomainCountRow} exists because {@link DomainCount} is
     * {@code @LarkBatisRow}.
     */
    default List<DomainCount> countByDomain(LarkBatisSession s, int minimum) {
        return s.query(
                SqlFragment.unsafeRawSql(
                        "SELECT SUBSTRING(usr_email FROM POSITION('@' IN usr_email) + 1) AS domain,"
                                + " COUNT(*) AS total FROM contacts"
                                + " GROUP BY domain HAVING COUNT(*) >= ? ORDER BY domain"),
                ps -> ps.setInt(1, minimum),
                DomainCountRow.READER);
    }
}
