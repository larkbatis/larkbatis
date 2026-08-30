package io.github.larkbatis.processor;

import com.google.testing.compile.Compilation;
import io.github.larkbatis.processor.ir.MapperModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.larkbatis.processor.TestSupport.assertSucceeded;
import static io.github.larkbatis.processor.TestSupport.compileFixturesWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mapper in, IR out — no code generation involved. */
class IrDumpTest {

    static final class CapturingProcessor extends LarkBatisProcessor {
        final List<String> dumps = new ArrayList<>();

        @Override
        protected void onModel(MapperModel model) {
            dumps.add(model.dump());
        }
    }

    @Test
    void specMapperIr() {
        CapturingProcessor processor = new CapturingProcessor();
        Compilation compilation = compileFixturesWith(processor,
                "com/example/app/User.java", "com/example/app/UserMapper.java");
        assertSucceeded(compilation);
        assertEquals(1, processor.dumps.size());
        assertEquals("""
                mapper com.example.app.UserMapper
                  SELECT findById -> ONE result:com.example.app.User reader:POSITIONAL_CANONICAL
                    text  |SELECT id, name, email, created_at FROM users WHERE id = |
                    bind  id = id : PRIM_LONG
                  INSERT insert -> UPDATE_COUNT
                    text  |INSERT INTO users (name, email, created_at) VALUES (|
                    bind  name = u.getName() : STRING
                    text  |, |
                    bind  email = u.getEmail() : STRING
                    text  |, |
                    bind  createdAt = u.getCreatedAt() : INSTANT
                    text  |)|
                    keys  [id] u.setId:PRIM_LONG
                """, processor.dumps.get(0));
    }
}
