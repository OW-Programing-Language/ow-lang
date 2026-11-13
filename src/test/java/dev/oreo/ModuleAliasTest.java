package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class ModuleAliasTest {
    private String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(source, "alias-test.ow").lexAll()).parseProgram());
        return output.toString();
    }

    private static final String BOX = """
            module_type BOX:
              type token
              fn make(x int) -> token
              fn read(x token) -> int
            module Original: BOX
              type token: int
              fn make(x int) -> token:
                return: x
              fn read(x token) -> int:
                return: x
            """;

    @Test public void aliasSharesOriginalOpaqueIdentity() {
        String output = run(BOX + """
                module Alias = Original
                main:
                  call: assert(Original.read(Alias.make(11)) == 11)
                  call: assert(Alias.read(Original.make(22)) == 22)
                  print: "PASS aliases"
                """);
        assertTrue(output.contains("PASS aliases"));
    }

    @Test public void nestedModuleAliasesCreateTypedPaths() {
        String output = run(BOX + """
                module Store.Inventory = Original
                module Store.Backup = Original
                main:
                  call: assert(Store.Inventory.read(Store.Backup.make(4)) == 4)
                  print: "PASS namespace"
                """);
        assertTrue(output.contains("PASS namespace"));
    }

    @Test public void nestedAliasAcceptedAsFunctorParameter() {
        String output = run(BOX + """
                module Company.Shop.Inventory = Original
                module_type RESULT:
                  fn get() -> int
                functor Inspect(O: BOX) -> RESULT:
                  fn get() -> int:
                    return: O.read(O.make(7))
                main:
                  set result RESULT: Inspect(Company.Shop.Inventory)
                  call: assert(result.get() == 7)
                  print: "PASS typed paths"
                """);
        assertTrue(output.contains("PASS typed paths"));
    }

    @Test public void forwardAliasChainsResolveToSameModule() {
        String output = run(BOX + """
                module Alpha = Beta
                module Beta = Original
                main:
                  call: assert(Alpha.read(Original.make(3)) == 3)
                  print: "PASS chains"
                """);
        assertTrue(output.contains("PASS chains"));
    }

    @Test public void aliasCyclesAreRejectedBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module A = B
                module B = A
                main:
                  print: "must never run"
                """));
        assertTrue(error.getMessage().contains("Cyclic module alias"));
    }

    @Test public void unknownAliasedModuleIsRejectedBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module Unknown = Missing
                main:
                  print: "must never run"
                """));
        assertTrue(error.getMessage().contains("Unknown module alias target Missing"));
    }

    @Test public void namespaceCannotShadowExistingModule() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BOX + """
                module Original.Child = Original
                main:
                  print: "must never run"
                """));
        assertTrue(error.getMessage().contains("namespace Original conflicts"));
    }

    @Test public void aliasesAreImportedAcrossFiles() throws Exception {
        Path dir = Files.createTempDirectory("ow-alias-import");
        Files.writeString(dir.resolve("base.ow"), BOX + "module Store.Inventory = Original\n");
        Path entry = dir.resolve("main.ow");
        Files.writeString(entry, """
                import "base.ow"
                main:
                  print: Store.Inventory.read(Original.make(5))
                """);
        Ast.Program program = Loader.load(entry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(program);
        assertTrue(output.toString().contains("5"));
    }
}
