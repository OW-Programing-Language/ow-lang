package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class AbstractModuleTypesTest {
    private String execute(String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(bytes)).run(
                new Parser(new Lexer(source).lexAll()).parseProgram());
        return bytes.toString();
    }

    private static final String BOXES = """
            module_type BOX:
              type token
              fn wrap(x int) -> token
              fn unwrap(x token) -> int
            module One: BOX
              type token: int
              fn wrap(x int) -> token:
                return: x
              fn unwrap(x token) -> int:
                return: x
            module Two: BOX
              type token: int
              fn wrap(x int) -> token:
                return: x
              fn unwrap(x token) -> int:
                return: x
            """;

    @Test public void representationIsSealed() {
        String output = execute(BOXES + """
                main:
                  set value: One.wrap(42)
                  call: assert(One.unwrap(value) == 42)
                  print: value
                """);
        assertTrue(output.contains("<abstract One.token>"));
        assertFalse(output.contains("42"));
    }

    @Test public void cannotPassRawIntAsAbstractType() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(BOXES + """
                main:
                  print: One.unwrap(42)
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void independentAbstractModuleTypesCannotMix() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(BOXES + """
                main:
                  print: Two.unwrap(One.wrap(7))
                """));
        assertTrue(error.getMessage().contains("Two.token"));
    }

    @Test public void missingRepresentationRejectedAtLoad() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute("""
                module_type BOX:
                  type token
                  fn wrap(x int) -> token
                module Broken: BOX
                  fn wrap(x int) -> token:
                    return: x
                main:
                  print: "must not run"
                """));
        assertTrue(error.getMessage().contains("must define abstract type BOX.token"));
    }

    @Test public void incorrectUnderlyingTypeOnReturnRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute("""
                module_type BOX:
                  type token
                  fn wrap(x int) -> token
                module Wrong: BOX
                  type token: int
                  fn wrap(x int) -> token:
                    return: "not an int"
                main:
                  set x: Wrong.wrap(3)
                """));
        assertTrue(error.getMessage().contains("representation of Wrong.token expects int"));
    }
}
