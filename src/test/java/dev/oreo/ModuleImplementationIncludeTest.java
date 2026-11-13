package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class ModuleImplementationIncludeTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source, "include-impl.ow").lexAll()).parseProgram());
        return out.toString();
    }

    @Test public void implementationIncludeAndLocalOverride() {
        String out = run("""
                module_type BASE:
                  fn read() -> int
                module Original: BASE
                  fn read() -> int:
                    return: 12
                module_type EXT:
                  include BASE
                  fn extra() -> int
                module New: EXT
                  include Original
                  fn extra() -> int:
                    return: 8
                main:
                  call: assert(New.read() == 12)
                  call: assert(New.extra() == 8)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void includedMethodsDelegateWithoutLosingOpaqueIdentity() {
        String out = run("""
                module_type BASE:
                  type token
                  fn wrap(x int) -> token
                  fn read(x token) -> int
                module Original: BASE
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn read(x token) -> int:
                    return: x
                module_type EXT:
                  include BASE
                module Reexport: EXT
                  include Original
                main:
                  set value: Reexport.wrap(6)
                  call: assert(Original.read(value) == 6)
                  call: assert(Reexport.read(Original.wrap(8)) == 8)
                  print: "PASS shared opaque identity"
                """);
        assertTrue(out.contains("PASS shared opaque identity"));
    }

    @Test public void functorCanIncludeItsModuleArgument() {
        String out = run("""
                module_type BASE:
                  fn read() -> int
                module Impl: BASE
                  fn read() -> int:
                    return: 21
                functor Export(O: BASE) -> BASE:
                  include O
                main:
                  set service BASE: Export(Impl)
                  call: assert(service.read() == 21)
                  print: "PASS functor include"
                """);
        assertTrue(out.contains("PASS functor include"));
    }

    @Test public void implementationIncludeRejectsCycle() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module A: API
                  include B
                module B: API
                  include A
                main:
                  print: "not run"
                """));
        assertTrue(ex.getMessage().contains("Cyclic implementation include"));
    }

    @Test public void implementationIncludeRejectsUnknownModule() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module Derived: API
                  include NotFound
                main:
                  print: "not run"
                """));
        assertTrue(ex.getMessage().contains("Unknown implementation include NotFound"));
    }

    @Test public void includeCannotExposePrivateMember() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type BASE:
                  fn get() -> int
                module Base: BASE
                  fn get() -> int:
                    return: 3
                  fn private secret() -> int:
                    return: 999
                module Derived: BASE
                  include Base
                main:
                  print: Derived.secret()
                """));
        assertTrue(ex.getMessage().contains("does not export secret"));
    }

    @Test public void includedAbstractRepresentationCannotBeRedefined() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type TOKEN:
                  type t
                  fn wrap(x int) -> t
                module Base: TOKEN
                  type t: int
                  fn wrap(x int) -> t:
                    return: x
                module Bad: TOKEN
                  include Base
                  type t: string
                main:
                  print: 1
                """));
        assertTrue(ex.getMessage().contains("must preserve included opaque type"));
    }
}
