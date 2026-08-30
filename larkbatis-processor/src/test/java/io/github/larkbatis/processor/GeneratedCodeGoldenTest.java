package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertGolden;
import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixtures;
import static io.github.larkbatis.processor.TestSupport.generatedSource;
import static io.github.larkbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden snapshots of the three generated shapes: mapper impl, row reader,
 * registry. The spec mapper mirrors the hand-written
 * landmark in larkbatis-runtime/src/test/.../handwritten/.
 */
class GeneratedCodeGoldenTest {

    @Test
    void specMapper() {
        Compilation compilation = compileFixtures(
                "com/example/app/User.java", "com/example/app/UserMapper.java");
        assertSucceeded(compilation);
        assertGolden("spec/UserMapper$$Impl.java",
                generatedSource(compilation, "com.example.app.UserMapper$$Impl"));
        assertGolden("spec/UserRow.java",
                generatedSource(compilation, "com.example.app.UserRow"));
        assertGolden("spec/LarkBatisMappers.java",
                generatedSource(compilation, "com.example.app.LarkBatisMappers"));
    }

    @Test
    void kitchenSinkMapper() {
        Compilation compilation = compileFixtures(
                "com/example/app/Order.java",
                "com/example/app/Status.java",
                "com/example/app/OrderMapper.java");
        assertSucceeded(compilation);
        assertGolden("kitchen/OrderMapper$$Impl.java",
                generatedSource(compilation, "com.example.app.OrderMapper$$Impl"));
        assertGolden("kitchen/OrderRow.java",
                generatedSource(compilation, "com.example.app.OrderRow"));

        // SELECT * downgrades that one statement to the name-based reader and
        // says so at build time
        String notes = messagesOf(compilation, javax.tools.Diagnostic.Kind.NOTE);
        assertTrue(notes.contains("OrderMapper.byId") && notes.contains("name-based"),
                () -> "expected a name-based downgrade note, got:\n" + notes);
    }
}
