package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class FunctorIdentityTest {
    private String execute(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source).lexAll()).parseProgram());
        return out.toString();
    }

    private static final String BASE = """
            module_type SOURCE:
              fn get() -> int
            module Seed: SOURCE
              fn get() -> int:
                return: 0
            module OtherSeed: SOURCE
              fn get() -> int:
                return: 0
            module_type BOX:
              type token
              fn wrap(x int) -> token
              fn unwrap(x token) -> int
            functor Make(S: SOURCE) -> BOX:
              type token: int
              fn wrap(x int) -> token:
                return: x + S.get()
              fn unwrap(x token) -> int:
                return: x
            functor_gen Fresh(S: SOURCE) -> BOX:
              type token: int
              fn wrap(x int) -> token:
                return: x
              fn unwrap(x token) -> int:
                return: x
            """;

    @Test public void applicativeApplicationSharesTypeIdentity() {
        String out = execute(BASE + """
                main:
                  set first: Make(Seed)
                  set second: Make(Seed)
                  call: assert(second.unwrap(first.wrap(5)) == 5)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void applicativeDifferentArgumentPathDoesNotShare() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(BASE + """
                main:
                  set first: Make(Seed)
                  set second: Make(OtherSeed)
                  print: second.unwrap(first.wrap(5))
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void generativeApplicationsAreAlwaysDistinct() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(BASE + """
                main:
                  set first: Fresh(Seed)
                  set second: Fresh(Seed)
                  print: second.unwrap(first.wrap(5))
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void generativeValueWorksWithItsOwnModule() {
        String out = execute(BASE + """
                main:
                  set first: Fresh(Seed)
                  call: assert(first.unwrap(first.wrap(5)) == 5)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }
}
