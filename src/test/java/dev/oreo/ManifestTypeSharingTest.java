package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class ManifestTypeSharingTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source).lexAll()).parseProgram());
        return out.toString();
    }

    @Test public void visibleRepresentationSharedAcrossModules() {
        String out = run("""
                module_type INTBOX:
                  type token: int
                  fn wrap(x int) -> token
                  fn unwrap(x token) -> int
                module A: INTBOX
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                module B: INTBOX
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                  fn unwrap(x token) -> int:
                    return: x
                main:
                  call: assert(B.unwrap(A.wrap(9)) == 9)
                  call: assert(B.unwrap(9) == 9)
                  print: A.wrap(9)
                """);
        assertTrue(out.trim().endsWith("9"));
        assertFalse(out.contains("<abstract"));
    }

    @Test public void manifestImplementationMustMatchSignature() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type INTBOX:
                  type token: int
                  fn wrap(x int) -> token
                module Wrong: INTBOX
                  type token: string
                  fn wrap(x int) -> token:
                    return: "bad"
                main:
                  print: "should not execute"
                """));
        assertTrue(e.getMessage().contains("must share manifest type INTBOX.token = int"));
    }

    @Test public void opaqueMemberCannotBeUsedAsManifestInt() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type OPAQUE:
                  type token
                  fn wrap(x int) -> token
                module A: OPAQUE
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                module_type PUBLIC:
                  type token: int
                  fn take(x token) -> int
                module B: PUBLIC
                  type token: int
                  fn take(x token) -> int:
                    return: x
                main:
                  print: B.take(A.wrap(3))
                """));
        assertTrue(e.getMessage().contains("manifest type PUBLIC.token expects int"));
    }

    @Test public void opaqueModuleDoesNotSatisfyTransparentSignature() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> run("""
                module_type OPAQUE:
                  type token
                  fn wrap(x int) -> token
                module_type TRANSPARENT:
                  type token: int
                  fn wrap(x int) -> token
                module Hidden: OPAQUE
                  type token: int
                  fn wrap(x int) -> token:
                    return: x
                module_type RESULT:
                  fn get() -> int
                functor NeedsInt(B: TRANSPARENT) -> RESULT:
                  fn get() -> int:
                    return: 10
                main:
                  set wrong: NeedsInt(Hidden)
                """));
        assertTrue(e.getMessage().contains("requires TRANSPARENT, got OPAQUE"));
    }
}
