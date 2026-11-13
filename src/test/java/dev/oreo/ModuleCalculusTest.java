package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class ModuleCalculusTest {
    private String run(String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Interpreter interpreter = new Interpreter(new PrintStream(bytes));
        interpreter.run(new Parser(new Lexer(source).lexAll()).parseProgram());
        return bytes.toString();
    }

    @Test public void nativeFunctorAppliesToTwoImplementations() {
        String output = run("""
                module_type ORDERED:
                  fn compare(a int, b int) -> int
                module_type CHOOSER:
                  fn best(a int, b int) -> int
                module Asc: ORDERED
                  fn compare(a int, b int) -> int:
                    if: a < b
                      return: 0 - 1
                    return: 1
                module Desc: ORDERED
                  fn compare(a int, b int) -> int:
                    if: a > b
                      return: 0 - 1
                    return: 1
                functor Make(O: ORDERED) -> CHOOSER:
                  fn best(a int, b int) -> int:
                    if: O.compare(a, b) < 1
                      return: a
                    return: b
                main:
                  set up CHOOSER: Make(Asc)
                  set down CHOOSER: Make(Desc)
                  print: up.best(2, 5)
                  print: down.best(2, 5)
                """);
        assertEquals("2\n5", output.trim().replace("\r\n", "\n"));
    }

    @Test public void structuralWidthSubtyping() {
        run("""
                module_type NARROW:
                  fn read() -> int
                module_type WIDE:
                  fn read() -> int
                  fn extra() -> int
                module_type RESULT:
                  fn get() -> int
                module Wide: WIDE
                  fn read() -> int:
                    return: 7
                  fn extra() -> int:
                    return: 9
                functor Wrap(A: NARROW) -> RESULT:
                  fn get() -> int:
                    return: A.read()
                main:
                  set result RESULT: Wrap(Wide)
                  call: assert(result.get() == 7)
                """);
    }

    @Test public void missingExportRejectedBeforeMain() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module Broken: API
                  fn missing() -> int:
                    return: 1
                main:
                  print: "must never execute"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("missing required export API.get"));
    }

    @Test public void methodTypeMismatchRejectedBeforeMain() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get(x int) -> int
                module Broken: API
                  fn get(x string) -> int:
                    return: 1
                main:
                  print: "must never execute"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("argument 1 type mismatch"));
    }

    @Test public void wrongFunctorModuleParameterRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module_type RESULT:
                  fn value() -> int
                module Empty: RESULT
                  fn value() -> int:
                    return: 1
                functor Need(A: API) -> RESULT:
                  fn value() -> int:
                    return: 7
                main:
                  set wrong: Need(Empty)
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("Functor Need requires API"));
    }

    @Test public void declaredSignatureHidesUndeclaredExports() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type PUBLIC:
                  fn get() -> int
                module Demo: PUBLIC
                  fn get() -> int:
                    return: 1
                  fn secret() -> int:
                    return: 999
                main:
                  print: Demo.secret()
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("does not export secret"));
    }

    @Test public void unknownFunctorSignatureRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type OUTPUT:
                  fn get() -> int
                functor Make(X: UNKNOWN) -> OUTPUT:
                  fn get() -> int:
                    return: 1
                main:
                  print: "must never execute"
                """));
        assertTrue(e.getMessage(), e.getMessage().contains("unknown input signature UNKNOWN"));
    }

    @Test public void duplicateModuleNamesRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type API:
                  fn get() -> int
                module Twice: API
                  fn get() -> int:
                    return: 1
                module Twice: API
                  fn get() -> int:
                    return: 2
                main:
                  print: "must never execute"
                """));
        assertTrue(e.getMessage().contains("Duplicate global declaration Twice"));
    }
}
