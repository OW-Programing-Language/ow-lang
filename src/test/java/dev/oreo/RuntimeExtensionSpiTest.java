package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class RuntimeExtensionSpiTest {
    private String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(
            new Parser(new Lexer(source, "ext-test.ow").lexAll()).parseProgram());
        return output.toString();
    }

    @Test public void unresolvedExtensionOperationFailsClosed() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> run("""
                main:
                  print: extension("not.installed")
                """));
        assertTrue(exception.getMessage().contains("No installed OW extension"));
    }

    @Test public void emptyOperationNameIsRejected() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> run("""
                main:
                  print: extension("")
                """));
        assertTrue(exception.getMessage().contains("nonempty string"));
    }

    @Test public void oldOwProgramsContinueToRunWithoutPlugins() {
        assertTrue(run("""
                main:
                  print: 42
                """).contains("42"));
    }
}
