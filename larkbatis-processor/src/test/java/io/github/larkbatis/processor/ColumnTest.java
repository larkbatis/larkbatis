package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixtures;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Column} names the column on the property itself, for the cases the
 * snake_case convention cannot reach. It is read on the field, the setter and
 * the getter, because the annotation targets FIELD and METHOD both and a user
 * who picks the site we do not read would get silence.
 */
class ColumnTest {

    /** All three sites feed the same name-based switch. */
    @Test
    void theReaderMatchesOnDeclaredColumnNames() {
        Compilation compilation = compileFixtures(
                "com/example/app/Contact.java",
                "com/example/app/ContactMapper.java");
        assertSucceeded(compilation);

        String reader = generatedSource(compilation, "com.example.app.ContactRow");
        assertTrue(reader.contains("case \"contactid\":"),    // @Column on the field
                () -> "expected the field's @Column in the switch:\n" + reader);
        assertTrue(reader.contains("case \"usremail\":"),     // @Column on the setter
                () -> "expected the setter's @Column in the switch:\n" + reader);
        assertTrue(reader.contains("case \"mobile\":"),       // @Column on the getter
                () -> "expected the getter's @Column in the switch:\n" + reader);
        assertTrue(!reader.contains("case \"id\":") && !reader.contains("case \"email\":")
                        && !reader.contains("case \"phone\":"),
                () -> "the property name must not still be a case label:\n" + reader);
    }

    /**
     * A select list naming the declared columns in declaration order is
     * canonical: literal indexes, no {@code int[]} anywhere.
     */
    @Test
    void aSelectListOfDeclaredColumnsStaysPositional() {
        Compilation compilation = compileFixtures(
                "com/example/app/Contact.java",
                "com/example/app/ContactMapper.java");
        assertSucceeded(compilation);

        String impl = generatedSource(compilation, "com.example.app.ContactMapper$$Impl");
        assertTrue(impl.contains("ContactRow.read(rs)"),
                () -> "expected the canonical positional read:\n" + impl);
        assertTrue(impl.contains("ContactRow.columns(rs)"),
                () -> "expected SELECT * to fall back to the name-based read:\n" + impl);
    }

    /** Two properties on one column is a duplicate case label — caught here, not in javac. */
    @Test
    void twoPropertiesOnOneColumnIsACompileError() {
        Compilation compilation = compile(bean("""
                @Column("email")
                private String contactEmail;
                private String email;

                public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
                public void setEmail(String email) { this.email = email; }
                """));
        assertFailedWith(compilation, "both read column \"email\"");
    }

    /** Two spellings of one column: the message has to explain why they are the same. */
    @Test
    void aColumnClashingWithAPropertyNameSaysWhyTheyAreTheSame() {
        Compilation compilation = compile(bean("""
                @Column("usr_email")
                private String email;
                private String usrEmail;

                public void setEmail(String email) { this.email = email; }
                public void setUsrEmail(String usrEmail) { this.usrEmail = usrEmail; }
                """));
        assertFailedWith(compilation, "are one column");
    }

    /** The field and the setter disagreeing is a typo, and reading either would be a guess. */
    @Test
    void twoDifferentColumnNamesOnOnePropertyIsACompileError() {
        Compilation compilation = compile(bean("""
                @Column("usr_email")
                private String email;

                @Column("user_email")
                public void setEmail(String email) { this.email = email; }
                """));
        assertFailedWith(compilation, "carries two different @Column names");
    }

    @Test
    void anEmptyColumnNameIsACompileError() {
        Compilation compilation = compile(bean("""
                @Column("")
                private String email;

                public void setEmail(String email) { this.email = email; }
                """));
        assertFailedWith(compilation, "has an empty column name");
    }

    private static JavaFileObject bean(String body) {
        return JavaFileObjects.forSourceString("com.example.app.Bean", """
                package com.example.app;

                import io.github.larkbatis.annotations.Column;
                import io.github.larkbatis.annotations.LarkBatisRow;

                @LarkBatisRow
                public class Bean {
                %s
                }
                """.formatted(body));
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new LarkBatisProcessor()).compile(sources);
    }
}
