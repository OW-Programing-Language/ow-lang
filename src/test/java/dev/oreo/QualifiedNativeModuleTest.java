package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class QualifiedNativeModuleTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source, "qualified.ow").lexAll()).parseProgram());
        return out.toString();
    }

    @Test public void qualifiedModuleDefinitionAndSignature() {
        String out = run("""
                module_type Store.API:
                  fn get() -> int
                module Store.Inventory: Store.API
                  fn get() -> int:
                    return: 42
                main:
                  print: Store.Inventory.get()
                """);
        assertTrue(out.contains("42"));
    }

    @Test public void signatureIncludeAcrossQualifiedPaths() {
        String out = run("""
                module_type Store.BASE:
                  fn read() -> int
                module_type Store.EXT:
                  include Store.BASE
                  fn name() -> string
                module Store.Inventory: Store.EXT
                  fn read() -> int:
                    return: 12
                  fn name() -> string:
                    return: "inventory"
                main:
                  call: assert(Store.Inventory.read() == 12)
                  print: Store.Inventory.name()
                """);
        assertTrue(out.contains("inventory"));
    }

    @Test public void functorAcceptsQualifiedModuleSignature() {
        String out = run("""
                module_type Shop.API:
                  fn read() -> int
                module Shop.Warehouse: Shop.API
                  fn read() -> int:
                    return: 15
                module_type RESULT:
                  fn get() -> int
                functor Use(Source: Shop.API) -> RESULT:
                  fn get() -> int:
                    return: Source.read()
                main:
                  set result RESULT: Use(Shop.Warehouse)
                  print: result.get()
                """);
        assertTrue(out.contains("15"));
    }

    @Test public void aliasPreservesQualifiedModuleIdentity() {
        String out = run("""
                module_type BOX:
                  type token
                  fn make(x int) -> token
                  fn read(x token) -> int
                module Shop.Warehouse: BOX
                  type token: int
                  fn make(x int) -> token:
                    return: x
                  fn read(x token) -> int:
                    return: x
                module Inventory = Shop.Warehouse
                module Store.Inventory = Inventory
                main:
                  call: assert(Shop.Warehouse.read(Store.Inventory.make(8)) == 8)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void qualifiedModulesPreserveDistinctOpaqueIdentity() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type BOX:
                  type token
                  fn make(x int) -> token
                  fn read(x token) -> int
                module Shop.A: BOX
                  type token: int
                  fn make(x int) -> token:
                    return: x
                  fn read(x token) -> int:
                    return: x
                module Shop.B: BOX
                  type token: int
                  fn make(x int) -> token:
                    return: x
                  fn read(x token) -> int:
                    return: x
                main:
                  print: "must not run"
                  print: Shop.B.read(Shop.A.make(8))
                """));
        assertTrue(ex.getMessage().contains("abstract type mismatch"));
    }

    @Test public void qualifiedNamespaceCollisionRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module Shop: API
                  fn get() -> int:
                    return: 1
                module Shop.Inventory: API
                  fn get() -> int:
                    return: 2
                main:
                  print: "should fail"
                """));
        assertTrue(ex.getMessage().contains("namespace Shop conflicts"));
    }
}
