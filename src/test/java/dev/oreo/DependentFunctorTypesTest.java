package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class DependentFunctorTypesTest {
    private String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(new Parser(new Lexer(source).lexAll()).parseProgram());
        return output.toString();
    }

    private static final String BASE = """
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
            functor Forward(S: BOX) -> BOX with_type token = S.token:
              type token: S.token
              fn wrap(x int) -> token:
                return: S.wrap(x)
              fn unwrap(x token) -> int:
                return: S.unwrap(x)
            """;

    @Test public void inputAndOutputShareSourceIdentity() {
        String out = run(BASE + """
                main:
                  set f: Forward(One)
                  call: assert(One.unwrap(f.wrap(12)) == 12)
                  call: assert(f.unwrap(One.wrap(7)) == 7)
                  print: "PASS"
                """);
        assertTrue(out.contains("PASS"));
    }

    @Test public void differentSourceIdentitiesRemainDistinct() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BASE + """
                main:
                  set f: Forward(One)
                  print: f.unwrap(Two.wrap(12))
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void differentFunctorApplicationsPreserveTheirSourceTypes() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BASE + """
                main:
                  set first: Forward(One)
                  set second: Forward(Two)
                  print: second.unwrap(first.wrap(1))
                """));
        assertTrue(error.getMessage().contains("Abstract type mismatch"));
    }

    @Test public void invalidTypeSharingContractFailsBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "type token: S.token", "type token: int") + """
                main:
                  print: "not reached"
                """));
        assertTrue(error.getMessage().contains("must implement with_type token = S.token"));
    }

    @Test public void unknownDependentMemberRejectedAtLoad() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "type token: S.token", "type token: S.unknown").replace(
                "with_type token = S.token", "with_type token = S.unknown") + """
                main:
                  print: "not reached"
                """));
        assertTrue(error.getMessage().contains("invalid dependent type path S.unknown"));
    }

    @Test public void wrongParameterNameRejectedAtLoad() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(BASE.replace(
                "with_type token = S.token", "with_type token = Other.token") + """
                main:
                  print: "not reached"
                """));
        assertTrue(error.getMessage().contains("invalid dependent type path Other.token"));
    }
}
