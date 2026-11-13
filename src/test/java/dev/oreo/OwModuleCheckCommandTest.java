package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class OwModuleCheckCommandTest {
    private Path source(String text) throws Exception {
        Path file = Files.createTempFile("ow-preflight-", ".ow");
        Files.writeString(file, text);
        return file;
    }

    @Test public void checkDoesNotRunMain() throws Exception {
        Path file = source("""
                module_type API:
                  fn read() -> int
                module Value: API
                  fn read() -> int:
                    return: 5
                main:
                  print: "SIDE_EFFECT_SHOULD_NOT_RUN"
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output));
            Main.main(new String[]{"check", file.toString()});
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("module type check passed"));
        assertFalse(output.toString().contains("SIDE_EFFECT_SHOULD_NOT_RUN"));
    }

    @Test public void checkRejectsSignatureMismatchBeforeExecution() throws Exception {
        Path file = source("""
                module_type API:
                  fn read(v int) -> int
                module Wrong: API
                  fn read(v string) -> int:
                    return: 1
                main:
                  print: "should not run"
                """);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Main.main(new String[]{"check", file.toString()}));
        assertTrue(error.getMessage().contains(file.getFileName() + ":4:6"));
        assertTrue(error.getMessage().contains("type mismatch"));
    }

    @Test public void checkRejectsStaticallyKnownOpaqueTypeEscape() throws Exception {
        Path file = source("""
                module_type BOX:
                  type token
                  fn wrap(v int) -> token
                module One: BOX
                  type token: int
                  fn wrap(v int) -> token:
                    return: v
                main:
                  set bad int: One.wrap(1)
                """);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Main.main(new String[]{"check", file.toString()}));
        assertTrue(error.getMessage().contains("opaque type"));
        assertTrue(error.getMessage().contains(file.getFileName() + ":9:7"));
    }

    @Test public void nativeBuildLowersStaticIntModulesWithoutDroppingCalls() throws Exception {
        Path file = source("""
                module_type API:
                  fn get() -> int
                module Value: API
                  fn get() -> int:
                    return: 3
                main:
                  print: Value.get()
                """);
        String ir = new OwCompiler().llvmIr(file, "native_static_test");
        assertTrue(ir.contains("define i32 @__ow_module_"));
        assertTrue(ir.contains("call i32 @__ow_module_"));
        assertTrue(ir.contains("ret i32 3"));
    }

    @Test public void nativeBuildRejectsFirstClassPackagesRatherThanDroppingThem() throws Exception {
        Path file = source("""
                module_type API:
                  fn get() -> int
                module Value: API
                  fn get() -> int:
                    return: 3
                main:
                  set packed: pack(Value, "API")
                  print: 1
                """);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> new OwCompiler().llvmIr(file, "native_dynamic_unsupported"));
        assertTrue(error.getMessage().contains("first-class module package values"));
    }

    @Test public void moduleCheckerRejectsImportCycleWithFileAndColumn() throws Exception {
        Path root = Files.createTempDirectory("ow-check-import-");
        Path first = root.resolve("first.ow");
        Path second = root.resolve("second.ow");
        Path entry = root.resolve("main.ow");
        Files.writeString(first, "import \"second.ow\"\n");
        Files.writeString(second, "import \"first.ow\"\n");
        Files.writeString(entry, "import \"first.ow\"\nmain:\n  print: 1\n");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Main.main(new String[]{"check", entry.toString()}));
        assertTrue(error.getMessage().contains("second.ow:1:1"));
        assertTrue(error.getMessage().contains("Import cycle"));
    }
}
