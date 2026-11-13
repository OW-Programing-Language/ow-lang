package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class StaticModuleValueCheckerTest {
    private ByteArrayOutputStream output;
    private String run(String source) {
        output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(source).lexAll()).parseProgram());
        return output.toString();
    }

    private static final String BOXES = """
            module_type BOX:
              type token
              fn wrap(x int) -> token
              fn unwrap(x token) -> int
            module First: BOX
              type token: int
              fn wrap(x int) -> token:
                return: x
              fn unwrap(x token) -> int:
                return: x
            module Second: BOX
              type token: int
              fn wrap(x int) -> token:
                return: x
              fn unwrap(x token) -> int:
                return: x
            """;

    @Test public void unrelatedAbstractTypesFailBeforeFirstPrint() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BOXES + """
                main:
                  print: "must not print"
                  set wrong: Second.unwrap(First.wrap(5))
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("abstract type mismatch"));
        assertEquals("", output.toString().trim());
    }

    @Test public void opaqueValuesCannotEscapeThroughArithmetic() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BOXES + """
                main:
                  print: "must not print"
                  print: First.wrap(5) + 1
                """));
        assertTrue(error.getMessage().contains("opaque abstract type escapes"));
        assertEquals("", output.toString().trim());
    }

    @Test public void integerIsNotAStringParameter() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn echo(value string) -> string
                module Greeter: API
                  fn echo(value string) -> string:
                    return: value
                main:
                  print: "must not print"
                  print: Greeter.echo(10)
                """));
        assertTrue(error.getMessage().contains("expected string but got int"));
        assertEquals("", output.toString().trim());
    }

    @Test public void applicativeTokensMatchStatically() {
        String result = run(BOXES + """
                functor Make(S: BOX) -> BOX:
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                main:
                  set first: Make(First)
                  set second: Make(First)
                  set t: first.wrap(5)
                  call: assert(second.unwrap(t) == 5)
                  print: "PASS"
                """);
        assertTrue(result.contains("PASS"));
    }

    @Test public void generativeTokensMismatchBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BOXES + """
                functor_gen Fresh(S: BOX) -> BOX:
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                main:
                  print: "must not print"
                  set first: Fresh(First)
                  set second: Fresh(First)
                  set t: first.wrap(5)
                  print: second.unwrap(t)
                """));
        assertTrue(error.getMessage().contains("abstract type mismatch"));
        assertEquals("", output.toString().trim());
    }

    @Test public void dependentResultSharesSourceTokenStatically() {
        String result = run(BOXES + """
                functor Forward(S: BOX) -> BOX with_type token = S.token:
                  type token: S.token
                  fn wrap(x int) -> token:
                    return: S.wrap(x)
                  fn unwrap(x token) -> int:
                    return: S.unwrap(x)
                main:
                  set forwarded: Forward(First)
                  call: assert(First.unwrap(forwarded.wrap(7)) == 7)
                  call: assert(forwarded.unwrap(First.wrap(8)) == 8)
                  print: "PASS"
                """);
        assertTrue(result.contains("PASS"));
    }

    @Test public void abstractTypeCannotBeAssignedToPrimitive() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BOXES + """
                main:
                  print: "must not print"
                  set number int: First.wrap(7)
                """));
        assertTrue(error.getMessage().contains("cannot be used as int"));
        assertEquals("", output.toString().trim());
    }
}
