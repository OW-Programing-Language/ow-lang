package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class FirstClassModulePackageTest {
    private String run(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(text, "packages.ow").lexAll()).parseProgram());
        return out.toString();
    }

    private static final String COMMON = """
            module_type READ:
              fn get() -> int
            module_type EXTRA:
              include READ
              fn secret() -> int
            module Store: EXTRA
              fn get() -> int:
                return: 42
              fn secret() -> int:
                return: 99
            """;

    @Test public void packingInArrayAndRecordPreservesIdentityAndVisibility() {
        assertTrue(run(COMMON + """
                main:
                  set packed: pack(Store, "READ")
                  set boxes: [packed]
                  set record: {saved: boxes[0]}
                  set restored READ: unpack(record.saved, "READ")
                  call: assert(restored.get() == 42)
                  print: "PASS"
                """).contains("PASS"));
    }

    @Test public void cannotWidenASealedModuleStatically() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(COMMON + """
                main:
                  set packed: pack(Store, "READ")
                  set restored: unpack(packed, "EXTRA")
                  print: "must not print"
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("cannot widen"));
    }

    @Test public void cannotWidenEvenWhenRetrievedFromArrayDynamically() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(COMMON + """
                main:
                  set boxes: [pack(Store, "READ")]
                  set illegal: unpack(boxes[0], "EXTRA")
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("cannot widen"));
    }

    @Test public void cannotPackIncompatibleSignature() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run("""
                module_type REQUIRED:
                  fn get() -> int
                module_type WRONG:
                  fn different() -> int
                module Impl: WRONG
                  fn different() -> int:
                    return: 1
                main:
                  set packed: pack(Impl, "REQUIRED")
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("cannot ascribe"));
    }

    @Test public void unknownSignatureRejectedDuringStaticCheck() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(COMMON + """
                main:
                  set packed: pack(Store, "MISSING")
                """));
        assertTrue(ex.getMessage().contains("Unknown module signature MISSING"));
    }

    @Test public void bareModuleCannotBeUnpacked() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(COMMON + """
                main:
                  set unpacked: unpack(Store, "READ")
                """));
        assertTrue(ex.getMessage().contains("unpack expects a packed module"));
    }

    @Test public void narrowedModuleCannotCallHiddenExport() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(COMMON + """
                main:
                  set package: pack(Store, "READ")
                  set restored: unpack(package, "READ")
                  print: restored.secret()
                """));
        assertTrue(ex.getMessage().contains("does not export secret"));
    }
}
