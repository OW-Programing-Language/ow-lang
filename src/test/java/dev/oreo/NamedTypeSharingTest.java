package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class NamedTypeSharingTest {
    private String run(String code) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(code).lexAll()).parseProgram());
        return out.toString();
    }

    private static final String BASE = """
            module_type BOX:
              type token
              fn wrap(value int) -> token
              fn unwrap(value token) -> int
            module A: BOX
              type token: int
              fn wrap(value int) -> token:
                return: value
              fn unwrap(value token) -> int:
                return: value
            module B: BOX with_type token = A.token:
              type token: A.token
              fn wrap(value int) -> token:
                return: A.wrap(value)
              fn unwrap(value token) -> int:
                return: A.unwrap(value)
            module C: BOX
              type token: int
              fn wrap(value int) -> token:
                return: value
              fn unwrap(value token) -> int:
                return: value
            """;

    @Test public void namedModuleSharesOpaqueTypeWithItsSource() {
        String out = run(BASE + """
                main:
                  call: assert(B.unwrap(A.wrap(10)) == 10)
                  call: assert(A.unwrap(B.wrap(12)) == 12)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void independentOpaqueTypesDoNotAccidentallyUnify() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(BASE + """
                main:
                  print: B.unwrap(C.wrap(4))
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void incompatibleConstraintImplementationRejectedBeforeMain() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "type token: A.token\n  fn wrap", "type token: int\n  fn wrap") + """
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("must implement with_type token = A.token"));
    }

    @Test public void unknownModulePathRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "A.token", "Missing.token") + """
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("Unknown module type path Missing.token"));
    }

    @Test public void unknownAbstractMemberRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "A.token", "A.notAToken") + """
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("Unknown abstract type member A.notAToken"));
    }

    @Test public void opaqueAliasCycleRejectedBeforeMain() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type BOX:
                  type token
                  fn id(value token) -> token
                module A: BOX with_type token = B.token:
                  type token: B.token
                  fn id(value token) -> token:
                    return: value
                module B: BOX with_type token = A.token:
                  type token: A.token
                  fn id(value token) -> token:
                    return: value
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("Cyclic type equality"));
    }

    @Test public void missingSignatureMethodFailsBeforeExecution() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn read(value int) -> int
                  fn write(value int) -> int
                module Broken: API
                  fn read(value int) -> int:
                    return: value
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("missing required export API.write"));
    }

    @Test public void parameterTypeEqualityIsRequired() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn read(value int) -> int
                module Broken: API
                  fn read(value string) -> int:
                    return: 1
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("type mismatch"));
    }

    @Test public void returnTypeEqualityIsRequired() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn read(value int) -> int
                module Broken: API
                  fn read(value int) -> string:
                    return: "wrong"
                main:
                  print: "should not run"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("return type mismatch"));
    }

    @Test public void structuralWidthAcceptsExtraExports() {
        String output = run("""
                module_type SMALL:
                  fn read(x int) -> int
                module_type LARGE:
                  fn read(x int) -> int
                  fn debug(x int) -> int
                module Big: LARGE
                  fn read(x int) -> int:
                    return: x
                  fn debug(x int) -> int:
                    return: x + 1
                module_type OUT:
                  fn get(x int) -> int
                functor Use(S: SMALL) -> OUT:
                  fn get(x int) -> int:
                    return: S.read(x)
                main:
                  set result OUT: Use(Big)
                  call: assert(result.get(4) == 4)
                  print: "PASS"
                """);
        assertTrue(output.contains("PASS"));
    }

    @Test public void structuralWidthRejectsDifferentReturnType() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type SMALL:
                  fn read(x int) -> int
                module_type LARGE:
                  fn read(x int) -> string
                  fn debug(x int) -> int
                module Big: LARGE
                  fn read(x int) -> string:
                    return: "hello"
                  fn debug(x int) -> int:
                    return: 1
                module_type OUT:
                  fn get() -> int
                functor Use(S: SMALL) -> OUT:
                  fn get() -> int:
                    return: 1
                main:
                  set result OUT: Use(Big)
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("requires SMALL, got LARGE"));
    }
}
