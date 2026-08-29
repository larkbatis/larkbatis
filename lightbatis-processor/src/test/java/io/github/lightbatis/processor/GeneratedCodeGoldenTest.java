package io.github.lightbatis.processor;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import static io.github.lightbatis.processor.TestSupport.assertGolden;
import static io.github.lightbatis.processor.TestSupport.assertSucceeded;
import static io.github.lightbatis.processor.TestSupport.compileFixtures;
import static io.github.lightbatis.processor.TestSupport.generatedSource;
import static io.github.lightbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden snapshots of the three generated shapes: mapper impl, row reader,
 * registry (build plan §05). The spec mapper mirrors the hand-written
 * landmark in lightbatis-runtime/src/test/.../handwritten/.
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
        assertGolden("spec/LightBatisMappers.java",
                generatedSource(compilation, "com.example.app.LightBatisMappers"));
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
        // says so at build time (design §04)
        String notes = messagesOf(compilation, javax.tools.Diagnostic.Kind.NOTE);
        assertTrue(notes.contains("OrderMapper.byId") && notes.contains("name-based"),
                () -> "expected a name-based downgrade note, got:\n" + notes);
    }
}
