package io.github.lightbatis.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

import static io.github.lightbatis.processor.TestSupport.assertFailedWith;
import static io.github.lightbatis.processor.TestSupport.assertSucceeded;
import static io.github.lightbatis.processor.TestSupport.messagesOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every "this is a compile error, not a runtime exception" promise in the
 * design needs a test here — a promise without a test does not exist yet
 * (build plan §03).
 */
class CompileFailTest {

    private static final JavaFileObject USER = JavaFileObjects.forResource("fixtures/com/example/app/User.java");

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new LightBatisProcessor()).compile(sources);
    }

    private static JavaFileObject mapper(String body) {
        return JavaFileObjects.forSourceString("com.example.app.BrokenMapper", """
                package com.example.app;

                import io.github.lightbatis.annotations.*;
                import java.util.List;

                public interface BrokenMapper {
                %s
                }
                """.formatted(body));
    }

    /** The flagship promise (design §08): ${} never accepts a String signature. */
    @Test
    void dollarWithStringParameterIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users ORDER BY ${sort}")
                List<User> all(String sort);
                """));
        assertFailedWith(compilation, "SqlFragment");
        assertFailedWith(compilation, "@OrderBy");
    }

    @Test
    void dollarWithBoxedIntSuggestsThePrimitive() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users LIMIT ${limit}")
                List<User> page(Integer limit);
                """));
        assertFailedWith(compilation, "primitive");
    }

    @Test
    void unsupportedParameterTypeIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users WHERE created_at < #{cutoff}")
                List<User> before(java.util.Date cutoff);
                """));
        assertFailedWith(compilation, "unsupported type");
    }

    @Test
    void unknownParameterNameListsWhatExists() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users WHERE id = #{userId}")
                User find(@Param("a") long a, @Param("b") long b);
                """));
        assertFailedWith(compilation, "does not match any parameter");
        assertFailedWith(compilation, "a, b");
    }

    /** MyBatis's runtime ExecutorException for keyProperty, moved to compile time (design §07). */
    @Test
    void wrongKeyPropertyIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Insert("INSERT INTO users (name) VALUES (#{name})")
                @Options(useGeneratedKeys = true, keyProperty = "nope", keyColumn = "id")
                int insert(User u);
                """));
        assertFailedWith(compilation, "No writable property \"nope\"");
    }

    @Test
    void mapParameterIsACompileError() {
        Compilation compilation = compile(mapper("""
                @Select("SELECT 1 FROM dual WHERE x = #{m.x}")
                long find(java.util.Map<String, Object> m);
                """));
        assertFailedWith(compilation, "Object/Map parameters were dropped");
    }

    @Test
    void ognlExpressionInHashIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id + 1}")
                User find(long id);
                """));
        assertFailedWith(compilation, "not a simple property path");
    }

    /**
     * An interface with no LightBatis annotations at all is not a mapper and
     * is left alone; but once one method is a statement, a bare abstract
     * sibling has no implementation anywhere — reject it.
     */
    @Test
    void abstractMethodWithoutStatementAnnotationIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Select("SELECT id, name, email, created_at FROM users WHERE id = #{id}")
                User findById(long id);

                User findSomething(long id);
                """));
        assertFailedWith(compilation, "default method");
    }

    @Test
    void dmlReturningBeanIsACompileError() {
        Compilation compilation = compile(USER, mapper("""
                @Delete("DELETE FROM users WHERE id = #{id}")
                User delete(long id);
                """));
        assertFailedWith(compilation, "must return int, long, boolean or void");
    }

    /** Red line 9: missing keyColumn is a build-time warning, not silence. */
    @Test
    void missingKeyColumnWarnsAtBuildTime() {
        Compilation compilation = compile(USER, mapper("""
                @Insert("INSERT INTO users (name) VALUES (#{name})")
                @Options(useGeneratedKeys = true, keyProperty = "id")
                int insert(User u);
                """));
        assertSucceeded(compilation);
        String warnings = messagesOf(compilation, Diagnostic.Kind.WARNING);
        assertTrue(warnings.contains("keyColumn") && warnings.contains("ROWID"),
                () -> "expected the Oracle-ROWID warning, got:\n" + warnings);
    }
}
