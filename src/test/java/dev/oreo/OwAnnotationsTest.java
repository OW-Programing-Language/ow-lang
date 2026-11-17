package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class OwAnnotationsTest {
    private String run(String src) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
            new Parser(new Lexer(src).lexAll()).parseProgram());
        return output.toString().replace("\r\n", "\n");
    }

    @Test public void catchesThrownExceptionAndRunsFinally() {
        assertEquals("caught: error\ncleanup\n", run("""
            main:
              try:
                throw: "error"
              catch message:
                print: "caught: " + message
              finally:
                print: "cleanup"
            """));
    }

    @Test public void finallyRunsWhenExceptionEscapes() {
        assertEquals("cleanup\nerror\n", run("""
            main:
              try:
                try:
                  throw: "error"
                finally:
                  print: "cleanup"
              catch message:
                print: message
            """));
    }

    @Test public void finallyRunsOnReturn() {
        assertEquals("cleanup\n42\n", run("""
            fn answer():
              try:
                return: 42
              finally:
                print: "cleanup"
            main:
              print: answer()
            """));
    }

    @Test public void failedAssertionsCanBeCaught() {
        assertEquals("Assertion failed: wrong\n", run("""
            main:
              try:
                call: assert(false, "wrong")
              catch message:
                print: message
            """));
    }

    @Test public void preservesLegacyTryCatch() {
        assertEquals("Division by zero\n", run("""
            main:
              try:
                print: 1 / 0
              catch message:
                print: message
            """));
    }

    @Test public void rejectsTryWithoutCatchOrFinally() {
        assertThrows(RuntimeException.class, () -> run("""
            main:
              try:
                print: 1
            """));
    }

    @Test public void lifecycleHooksAndClassTestsRun() {
        String source = """
            class Example:
              field count
              @BeforeEach
              fn setup():
                set this.count: 3
              @AfterEach
              fn teardown():
                call: assert(this.count == 3)
              @Test
              fn first():
                call: assert(this.count == 3)
              @Test
              fn second():
                call: assert(this.count == 3)
            """;
        Ast.Program program = new Parser(new Lexer(source).lexAll()).parseProgram();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertEquals(0, new Interpreter(new PrintStream(buffer)).runTests(program));
        assertTrue(buffer.toString().contains("2 passed, 0 failed"));
    }

    @Test public void aFailedTestDoesNotAbortFollowingTests() {
        String source = """
            @Test
            fn fails():
              throw: "expected failure"
            @Test
            fn passes():
              call: assert(true)
            """;
        Ast.Program program = new Parser(new Lexer(source).lexAll()).parseProgram();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertEquals(1, new Interpreter(new PrintStream(buffer)).runTests(program));
        assertTrue(buffer.toString().contains("[FAIL] fails"));
        assertTrue(buffer.toString().contains("[PASS] passes"));
    }

    @Test public void invalidAnnotationIsRejected() {
        assertThrows(RuntimeException.class, () -> new Parser(new Lexer("""
            @Unknown
            fn test():
              print: 1
            """).lexAll()).parseProgram());
    }

    @Test public void loaderPermitsTestFilesWithoutMain() throws Exception {
        Path file = Files.createTempFile("OWUnitTest", ".ow");
        Files.writeString(file, "@Test\nfn example():\n  call: assert(true)\n");
        assertEquals(1, Loader.loadTests(file).functions.size());
        assertThrows(RuntimeException.class, () -> Loader.load(file));
    }
}
