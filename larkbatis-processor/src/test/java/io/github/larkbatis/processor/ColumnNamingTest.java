package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertFailedWith;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static io.github.larkbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Alarkbatis.mapUnderscoreToCamelCase}, the one MyBatis setting whose
 * answer a migrating build has to be able to carry across. On by default,
 * which is not MyBatis's default: a mapper written against MyBatis with the
 * setting off still reads the columns it read before, but columns MyBatis left
 * unset start being populated — a behaviour change no test names, and the
 * reason turning it off has to be possible.
 *
 * <p>Both modes are decided at build time and baked into the generated reader,
 * so what is asserted here is the generated source: the label expression the
 * column resolver switches on, and the {@code case} labels built from the same
 * convention.
 */
class ColumnNamingTest {

    private static final String[] FIXTURES = {
            "com/example/app/Profile.java",
            "com/example/app/ProfileMapper.java",
    };

    private static Compilation compile(String... options) {
        return compileFixturesWith(new LarkBatisProcessor(), List.of(options), FIXTURES);
    }

    private static String readerOf(Compilation compilation) {
        assertSucceeded(compilation);
        return generatedSource(compilation, "com.example.app.ProfileRow");
    }

    // --- the default: underscores ignored on both sides ------------------------

    @Test
    void byDefaultTheResolverStripsUnderscoresFromTheLabel() {
        String reader = readerOf(compile());

        assertTrue(reader.contains("md.getColumnLabel(i).replace(\"_\", \"\").toLowerCase"),
                reader);
        assertTrue(reader.contains("case \"username\":"), reader);
        // @Column("zip_code") is normalized the same way, so it matches a
        // label spelled either way
        assertTrue(reader.contains("case \"zipcode\":"), reader);
    }

    @Test
    void byDefaultEveryUnderscoredColumnReachesItsProperty() {
        String impl = implOf(compile());

        assertTrue(impl.contains("ProfileRow.read(rs)"), "expected the canonical read: " + impl);
    }

    // --- off: underscores are significant --------------------------------------

    @Test
    void switchedOffTheResolverMatchesTheLabelAsWritten() {
        String reader = readerOf(compile("-Alarkbatis.mapUnderscoreToCamelCase=false"));

        assertFalse(reader.contains(".replace(\"_\", \"\")"),
                "the label must reach the switch as written: " + reader);
        assertTrue(reader.contains("md.getColumnLabel(i).toLowerCase"), reader);
        assertTrue(reader.contains("case \"username\":"), reader);
        // @Column keeps its underscores, so this one still matches "zip_code"
        assertTrue(reader.contains("case \"zip_code\":"), reader);
    }

    /**
     * The behaviour that matters: {@code user_name} stops reaching
     * {@code setUserName}, exactly as MyBatis leaves it unset with the setting
     * off, while {@code zip_code} still reaches the property that names it
     * through {@code @Column}. Two of three columns map, so the reader is no
     * longer the canonical one.
     */
    @Test
    void switchedOffAnUnderscoredColumnNoLongerReachesACamelCaseProperty() {
        String impl = implOf(compile("-Alarkbatis.mapUnderscoreToCamelCase=false"));

        assertFalse(impl.contains("ProfileRow.read(rs)"),
                "the canonical read would mean user_name still mapped: " + impl);
        assertTrue(impl.contains("ProfileRow.read(rs, "), impl);
    }

    /**
     * A whole select list that matches nothing is already an error, and with
     * the setting off it is the error a migrating build wants: it names the
     * columns it could not place instead of quietly returning rows of
     * defaults.
     */
    @Test
    void switchedOffASelectListThatMatchesNothingIsStillAnError() {
        Compilation compilation = compileFixturesWith(new LarkBatisProcessor(),
                List.of("-Alarkbatis.mapUnderscoreToCamelCase=false"),
                "com/example/app/User.java",
                "com/example/app/UnderscoreOnlyMapper.java");

        assertFailedWith(compilation, "No select-list column matches any property");
    }

    /**
     * The column is dropped, the property keeps its default, and MyBatis says
     * nothing about either — the failure arrives as a null in production. This
     * NOTE is the one place a build can name it, and it can only fire in a
     * build that asked for this mode.
     */
    @Test
    void switchedOffTheDroppedColumnsAreNamed() {
        Compilation compilation = compile("-Alarkbatis.mapUnderscoreToCamelCase=false");
        assertSucceeded(compilation);

        String notes = messagesOf(compilation, Diagnostic.Kind.NOTE);
        assertTrue(notes.contains("user_name → userName"), notes);
        assertTrue(notes.contains("Alias the column in the SQL, or name it with @Column"), notes);
        assertFalse(notes.contains("zip_code"),
                "zip_code reaches its property through @Column and must not be reported: " + notes);
    }

    @Test
    void byDefaultNothingIsReportedAsDropped() {
        Compilation compilation = compile();
        assertSucceeded(compilation);

        assertFalse(messagesOf(compilation, Diagnostic.Kind.NOTE)
                .contains("mapUnderscoreToCamelCase is off"));
    }

    // --- the option itself -------------------------------------------------------

    @Test
    void trueIsTheDefaultSpeltOut() {
        assertTrue(readerOf(compile("-Alarkbatis.mapUnderscoreToCamelCase=TRUE"))
                .contains(".replace(\"_\", \"\")"));
    }

    /**
     * Not a silent fallback to the default. This option decides which columns
     * reach which setters, so a mistyped {@code no} read as {@code true} would
     * not fail the build — it would map columns the author asked to leave
     * alone, and the first sign of it would be data.
     */
    @Test
    void aValueThatIsNotABooleanIsAnError() {
        assertFailedWith(compile("-Alarkbatis.mapUnderscoreToCamelCase=no"),
                "is not a boolean — write true or false");
    }

    private static String implOf(Compilation compilation) {
        assertSucceeded(compilation);
        return generatedSource(compilation, "com.example.app.ProfileMapper$$Impl");
    }
}
