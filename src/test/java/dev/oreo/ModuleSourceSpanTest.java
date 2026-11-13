package dev.oreo;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.Assert.*;

public class ModuleSourceSpanTest {
    private void parseRun(String code) {
        new Interpreter(System.out).run(new Parser(
                new Lexer(code, "sample.ow").lexAll()).parseProgram());
    }

    @Test public void signatureMismatchHasSourceSpan() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parseRun("""
                module_type API:
                  fn get(x int) -> int
                module Bad: API
                  fn get(x string) -> int:
                    return: 1
                main:
                  print: 1
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("sample.ow:4:6"));
        assertTrue(error.getMessage().contains("type mismatch"));
    }

    @Test public void missingExportPointsToModuleDeclaration() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parseRun("""
                module_type API:
                  fn get() -> int
                module Bad: API
                  fn other() -> int:
                    return: 1
                main:
                  print: 1
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("sample.ow:3:8"));
    }

    @Test public void incompatibleApplicationHasColumnAndSource() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parseRun("""
                module_type INPUT:
                  fn get() -> int
                module_type WRONG:
                  fn other() -> int
                module M: WRONG
                  fn other() -> int:
                    return: 1
                module_type RESULT:
                  fn get() -> int
                functor Use(A: INPUT) -> RESULT:
                  fn get() -> int:
                    return: A.get()
                main:
                  set bad: Use(M)
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("sample.ow:14:12"));
        assertTrue(error.getMessage().contains("requires INPUT"));
    }

    @Test public void abstractTypeEscapeHasOperatorSourceSpan() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parseRun("""
                module_type BOX:
                  type t
                  fn wrap(x int) -> t
                module A: BOX
                  type t: int
                  fn wrap(x int) -> t:
                    return: x
                main:
                  set bad: A.wrap(1) + 3
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("sample.ow:9:22"));
        assertTrue(error.getMessage().contains("opaque abstract type escapes"));
    }

    @Test public void missingImportHasFileLineAndColumn() throws IOException {
        Path dir = Files.createTempDirectory("ow-span-test");
        Path file = dir.resolve("main.ow");
        Files.writeString(file, "import \"missing.ow\"\nmain:\n  print: 1\n");
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(file));
        assertTrue(error.getMessage(), error.getMessage().contains("main.ow:1:1"));
    }

    @Test public void cyclicImportHasFileLineAndColumn() throws IOException {
        Path dir = Files.createTempDirectory("ow-cycle-span");
        Path first = dir.resolve("one.ow");
        Path second = dir.resolve("two.ow");
        Path entry = dir.resolve("main.ow");
        Files.writeString(first, "import \"two.ow\"\n");
        Files.writeString(second, "import \"one.ow\"\n");
        Files.writeString(entry, "import \"one.ow\"\nmain:\n  print: 1\n");
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(entry));
        assertTrue(error.getMessage(), error.getMessage().contains("two.ow:1:1"));
        assertTrue(error.getMessage().contains("Import cycle"));
    }

    @Test public void forbiddenImportHasFileLineAndColumn() throws IOException {
        Path dir = Files.createTempDirectory("ow-boundary-span");
        Path infra = dir.resolve("inventory/infra/Internal.ow");
        Path consumer = dir.resolve("orders/infra/Consumer.ow");
        Path main = dir.resolve("main.ow");
        Files.createDirectories(infra.getParent());
        Files.createDirectories(consumer.getParent());
        Files.writeString(infra, "module_type API:\n  fn get() -> int\n");
        Files.writeString(consumer, "import \"../../inventory/infra/Internal.ow\"\n");
        Files.writeString(main, "import \"orders/infra/Consumer.ow\"\nmain:\n  print: 1\n");
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(main));
        assertTrue(error.getMessage(), error.getMessage().contains("Consumer.ow:1:1"));
        assertTrue(error.getMessage().contains("module boundary violation"));
    }

    @Test public void tokenSpanHasExclusiveEndColumn() {
        var tokens = new Lexer("  set number int: 5", "field.ow").lexAll();
        var identifier = tokens.stream().filter(t -> t.text.equals("number")).findFirst().orElseThrow();
        assertEquals("field.ow", identifier.source);
        assertEquals(7, identifier.column);
        assertEquals(13, identifier.endColumn);
    }
}
