package com.example.app;

import io.github.larkbatis.annotations.Insert;
import io.github.larkbatis.annotations.Param;
import io.github.larkbatis.annotations.Select;
import java.util.List;

public interface AccountMapper {

    @Select("SELECT id, balance, owner FROM accounts WHERE id = #{id}")
    Account find(long id);

    @Select("SELECT id, balance, owner FROM accounts")
    List<Account> all();

    /** The bind picks the handler up off the property it reads. */
    @Insert("INSERT INTO accounts (id, balance, owner) VALUES (#{a.id}, #{a.balance}, #{a.owner})")
    int insert(@Param("a") Account a);

    /** An inline typeHandler, the form a migrated mapper already carries. */
    @Select("SELECT id FROM accounts WHERE balance > #{floor, typeHandler=com.example.app.MoneyHandler}")
    List<Long> richerThan(Money floor);
}
