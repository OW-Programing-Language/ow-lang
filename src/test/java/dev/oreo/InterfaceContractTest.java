package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;

/** Interface declarations are contracts, not just nominal labels. */
public class InterfaceContractTest {
    private String run(String program) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Interpreter interpreter = new Interpreter(new PrintStream(output));
        interpreter.run(new Parser(new Lexer(program).lexAll()).parseProgram());
        return output.toString();
    }

    @Test public void completeInterfaceRuns() {
        String result = run("""
                interface Counter:
                  fn get()
                  fn add(n)
                class MemoryCounter(Counter):
                  field value
                  fn new():
                    set this.value: 0
                  fn get():
                    return: this.value
                  fn add(n):
                    set this.value: this.value + n
                    return: 0
                main:
                  set counter Counter: new MemoryCounter()
                  call: counter.add(2)
                  print: counter.get()
                """);
        assertTrue(result, result.contains("2"));
    }

    @Test public void missingInterfaceMethodFailsBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                interface Store:
                  fn read(key)
                class Broken(Store):
                  fn new():
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("does not implement Store.read/1"));
    }

    @Test public void wrongArityFailsBeforeMain() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                interface Store:
                  fn read(key)
                class Broken(Store):
                  fn read():
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("Store requires 1"));
    }

    @Test public void privateImplementationCannotSatisfyPublicInterface() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                interface Store:
                  fn read(key)
                class Broken(Store):
                  fn private read(key):
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage(), error.getMessage().contains("private"));
    }

    @Test public void inheritedImplementationSatisfiesContract() {
        String result = run("""
                interface Reader:
                  fn read(k)
                class Base(Reader):
                  fn read(k):
                    return: k
                class Derived(Base):
                  fn new():
                    return: 0
                main:
                  set r Reader: new Derived()
                  print: r.read(5)
                """);
        assertTrue(result.contains("5"));
    }

    @Test public void subclassCannotHideRequiredMethodAsPrivate() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                interface Reader:
                  fn read(k)
                class Base(Reader):
                  fn read(k):
                    return: k
                class Child(Base):
                  fn private read(k):
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage().contains("private"));
    }

    @Test public void unknownBaseTypeRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                class Mistake(MissingInterface):
                  fn new():
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage().contains("unknown class or interface"));
    }

    @Test public void cyclicInheritanceRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run("""
                class A(B):
                  fn new():
                    return: 0
                class B(A):
                  fn new():
                    return: 0
                main:
                  print: "should not execute"
                """));
        assertTrue(error.getMessage().contains("Cyclic class inheritance"));
    }
}
