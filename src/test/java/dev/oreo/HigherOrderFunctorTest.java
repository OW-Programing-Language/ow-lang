package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class HigherOrderFunctorTest {
    private String run(String code) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(code).lexAll()).parseProgram());
        return out.toString();
    }

    private static final String BASE = """
            module_type SOURCE:
              fn get() -> int
            module Source: SOURCE
              fn get() -> int:
                return: 5
            module_type RESULT:
              fn result() -> int
            functor_type MAKER(SOURCE) -> RESULT
            functor Make(S: SOURCE) -> RESULT:
              fn result() -> int:
                return: S.get()
            functor Decorate(F: MAKER) -> RESULT:
              fn result() -> int:
                set inner: F(Source)
                return: inner.result() + 1
            functor Identity(F: MAKER) -> MAKER:
              return_functor: F
            """;

    @Test public void acceptedFunctorArgumentIsInvoked() {
        String output = run(BASE + """
                main:
                  set d RESULT: Decorate(Make)
                  call: assert(d.result() == 6)
                  print: "PASS"
                """);
        assertTrue(output.contains("PASS"));
    }

    @Test public void canReturnAndReapplyFunctor() {
        String output = run(BASE + """
                main:
                  set returned MAKER: Identity(Make)
                  set m RESULT: returned(Source)
                  call: assert(m.result() == 5)
                  print: "PASS"
                """);
        assertTrue(output.contains("PASS"));
    }

    @Test public void functorInputMustMatchPublicSignature() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(BASE + """
                module_type OTHER:
                  fn other() -> int
                functor Wrong(S: SOURCE) -> OTHER:
                  fn other() -> int:
                    return: 10
                main:
                  set result: Decorate(Wrong)
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("requires functor MAKER"));
    }

    @Test public void wrongReturnedFunctorRejectedBeforeMain() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(BASE + """
                module_type OTHER:
                  fn other() -> int
                functor Wrong(S: SOURCE) -> OTHER:
                  fn other() -> int:
                    return: 10
                functor Bad(F: MAKER) -> MAKER:
                  return_functor: Wrong
                main:
                  print: "should never happen"
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("returns incompatible functor Wrong"));
    }

    @Test public void unknownFunctorTypeRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(BASE + """
                functor Broken(F: UNKNOWN_FACTORY) -> RESULT:
                  fn result() -> int:
                    return: 0
                main:
                  print: "should never happen"
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("unknown input signature UNKNOWN_FACTORY"));
    }

    @Test public void nonFunctorCannotBePassedToFunctorParameter() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(BASE + """
                main:
                  set result: Decorate(Source)
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("requires functor MAKER"));
    }

    @Test public void typedFunctorVariablesCheckSignature() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(BASE + """
                main:
                  set impossible MAKER: Source
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("expects MAKER"));
    }
}
