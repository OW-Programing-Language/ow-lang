package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class ModuleTypeIncludeTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source, "include-test.ow").lexAll()).parseProgram());
        return out.toString();
    }

    private static final String LIFE = """
            module_type LIFE:
              fn name() -> string
              fn start() -> int
            """;

    @Test public void includedSignatureRequiresAllInheritedMethods() {
        String output = run(LIFE + """
                module_type INVENTORY:
                  include LIFE
                  fn stock() -> int
                module Warehouse: INVENTORY
                  fn name() -> string:
                    return: "warehouse"
                  fn start() -> int:
                    return: 0
                  fn stock() -> int:
                    return: 12
                main:
                  call: assert(Warehouse.stock() == 12)
                  print: "PASS signature inclusion"
                """);
        assertTrue(output.contains("PASS signature inclusion"));
    }

    @Test public void inheritedAbstractTypeCanBeUsedByDerivedSignature() {
        String output = run("""
                module_type OPAQUE:
                  type token
                  fn wrap(x int) -> token
                module_type BOX:
                  include OPAQUE
                  fn unwrap(x token) -> int
                module X: BOX
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                main:
                  call: assert(X.unwrap(X.wrap(5)) == 5)
                  print: "PASS type include"
                """);
        assertTrue(output.contains("PASS type include"));
    }

    @Test public void missingIncludedMemberIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(LIFE + """
                module_type INVENTORY:
                  include LIFE
                module Broken: INVENTORY
                  fn start() -> int:
                    return: 0
                main:
                  print: "never"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("missing required export INVENTORY.name"));
    }

    @Test public void unknownIncludedSignatureIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module_type FOO:
                  include NOT_FOUND
                main:
                  print: "never"
                """));
        assertTrue(error.getMessage().contains("Unknown module_type include NOT_FOUND"));
    }

    @Test public void cyclicIncludeIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module_type A:
                  include B
                module_type B:
                  include A
                main:
                  print: "never"
                """));
        assertTrue(error.getMessage().contains("Cyclic module_type include"));
    }

    @Test public void conflictingOverriddenMethodIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module_type A:
                  fn get() -> int
                module_type B:
                  include A
                  fn get() -> string
                main:
                  print: "never"
                """));
        assertTrue(error.getMessage().contains("Conflicting override of included export B.get"));
    }

    @Test public void importedSignaturesAreResolvedAcrossFiles() throws Exception {
        Path dir = Files.createTempDirectory("ow-signature-include");
        Path base = dir.resolve("base.ow");
        Path main = dir.resolve("main.ow");
        Files.writeString(base, LIFE);
        Files.writeString(main, """
                import "base.ow"
                module_type CHILD:
                  include LIFE
                module Ready: CHILD
                  fn name() -> string:
                    return: "ready"
                  fn start() -> int:
                    return: 0
                main:
                  print: Ready.name()
                """);
        Ast.Program program = Loader.load(main);
        ModuleTypeChecker.check(program);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(program);
        assertTrue(out.toString().contains("ready"));
    }
}
