package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class SetMakeComparisonTest {
    private String execute(String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
                new Parser(new Lexer(input).lexAll()).parseProgram());
        return output.toString();
    }

    private static final String DECL = """
            module_type ORDERED:
              type t
              fn make(value int) -> t
              fn compare(a t, b t) -> int
            module IntOrdered: ORDERED
              type t: int
              fn make(value int) -> t:
                return: value
              fn compare(a t, b t) -> int:
                if: a < b
                  return: 0 - 1
                if: a > b
                  return: 1
                return: 0
            module_type RESULT:
              type element
              fn take(value element) -> int
            functor Make(O: ORDERED) -> RESULT with_type element = O.t:
              type element: O.t
              fn take(value element) -> int:
                return: O.compare(value, value)
            """;

    @Test public void appliedSetSharesTypeOfItsOrderingModule() {
        String out = execute(DECL + """
                main:
                  set result: Make(IntOrdered)
                  call: assert(result.take(IntOrdered.make(8)) == 0)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void wrongConcreteElementIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(DECL + """
                main:
                  set result: Make(IntOrdered)
                  print: result.take(42)
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void differentOrderingModuleDoesNotShareTypeIdentity() {
        String different = """
                module OtherOrdered: ORDERED
                  type t: int
                  fn make(value int) -> t:
                    return: value
                  fn compare(a t, b t) -> int:
                    return: a - b
                """;
        RuntimeException error = assertThrows(RuntimeException.class, () -> execute(DECL + different + """
                main:
                  set result: Make(IntOrdered)
                  print: result.take(OtherOrdered.make(4))
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }
}
