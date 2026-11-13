package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class StaticHigherOrderFunctorTest {
    private static final String BASE = """
            module_type SOURCE:
              fn get() -> int
            module Seed: SOURCE
              fn get() -> int:
                return: 5
            module_type WRONG:
              fn other() -> int
            module Wrong: WRONG
              fn other() -> int:
                return: 7
            module_type RESULT:
              fn result() -> int
            functor_type MAKER(SOURCE) -> RESULT
            functor Make(S: SOURCE) -> RESULT:
              fn result() -> int:
                return: S.get()
            functor Identity(F: MAKER) -> MAKER:
              return_functor: F
            """;

    private Ast.Program parse(String suffix) {
        return new Parser(new Lexer(BASE + suffix, "higher-static.ow").lexAll()).parseProgram();
    }

    @Test public void returnedFunctorIsKnownAndCanBeAppliedStatically() {
        Ast.Program program = parse("""
                main:
                  set selected MAKER: Identity(Make)
                  set service RESULT: selected(Seed)
                  print: service.result()
                """);
        ModuleTypeChecker.check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(output)).run(program);
        assertTrue(output.toString().contains("5"));
    }

    @Test public void returnedFunctorRejectsWrongModuleBeforeExecution() {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                ModuleTypeChecker.check(parse("""
                main:
                  set selected MAKER: Identity(Make)
                  print: "must not run"
                  set bad: selected(Wrong)
                """)));
        assertTrue(error.getMessage(), error.getMessage().contains("requires SOURCE, got WRONG"));
    }

    @Test public void returnedFunctorReassignmentIsTracked() {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                ModuleTypeChecker.check(parse("""
                main:
                  set selected MAKER: Identity(Make)
                  set another: selected
                  print: "must not run"
                  set bad: another(Wrong)
                """)));
        assertTrue(error.getMessage().contains("requires SOURCE, got WRONG"));
    }

    @Test public void unrelatedReturnedFunctorTypeCannotBeAscribed() {
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                ModuleTypeChecker.check(parse("""
                module_type OTHER:
                  fn read() -> int
                functor_type OTHER_MAKER(SOURCE) -> OTHER
                main:
                  set bad OTHER_MAKER: Identity(Make)
                """)));
        assertTrue(error.getMessage(), error.getMessage().contains("does not satisfy OTHER_MAKER"));
    }

    @Test public void shadowedFunctorValueIsNoLongerAssumedCallable() {
        // A reassigned non-functor must not retain the old static functor value.
        ModuleTypeChecker.check(parse("""
                main:
                  set selected MAKER: Identity(Make)
                  set selected: 1
                  print: selected
                """));
    }
}
