package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class LexicalNestedModuleTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source, "lexical.ow").lexAll()).parseProgram());
        return out.toString();
    }

    private static final String PREFIX = """
            module_type API:
              fn get() -> int
            module_type OUTER:
              module Public: API
              fn total() -> int
            """;

    @Test public void publicAndPrivateLexicalChildrenAreDistinct() {
        String output = run(PREFIX + """
                module Outer: OUTER
                  module_type SECRET:
                    fn hidden() -> int
                  module Public: API
                    fn get() -> int:
                      return: 7
                  module Private: SECRET
                    fn hidden() -> int:
                      return: 8
                  fn total() -> int:
                    return: Public.get() + Private.hidden()
                main:
                  call: assert(Outer.total() == 15)
                  call: assert(Outer.Public.get() == 7)
                  print: "PASS lexical modules"
                """);
        assertTrue(output.contains("PASS lexical modules"));
    }

    @Test public void privateLexicalChildCannotBeReadExternally() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(PREFIX + """
                module Outer: OUTER
                  module Public: API
                    fn get() -> int:
                      return: 1
                  module Private: API
                    fn get() -> int:
                      return: 9
                  fn total() -> int:
                    return: Private.get()
                main:
                  print: Outer.Private.get()
                """));
        assertTrue(ex.getMessage(), ex.getMessage().contains("Private nested module"));
    }

    @Test public void missingPublicLexicalChildIsRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(PREFIX + """
                module Outer: OUTER
                  fn total() -> int:
                    return: 1
                main:
                  print: 1
                """));
        assertTrue(ex.getMessage().contains("missing required nested export Public"));
    }

    @Test public void mismatchedChildSignatureIsRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(PREFIX + """
                module_type WRONG:
                  fn bad() -> int
                module Outer: OUTER
                  module Public: WRONG
                    fn bad() -> int:
                      return: 1
                  fn total() -> int:
                    return: 0
                main:
                  print: 1
                """));
        assertTrue(ex.getMessage().contains("missing required nested export Public"));
    }

    @Test public void nestedLocalModuleTypeIsScoped() {
        String output = run("""
                module_type OUTER:
                  fn get() -> int
                module Outer: OUTER
                  module_type INTERNAL:
                    fn secret() -> int
                  module Child: INTERNAL
                    fn secret() -> int:
                      return: 11
                  fn get() -> int:
                    return: Child.secret()
                main:
                  call: assert(Outer.get() == 11)
                  print: "PASS lexical signatures"
                """);
        assertTrue(output.contains("PASS lexical signatures"));
    }

    @Test public void twoNestedLevelsWorkAndRemainSealed() {
        String output = run("""
                module_type VALUE:
                  fn read() -> int
                module_type MIDDLE:
                  module Leaf: VALUE
                module_type ROOT:
                  module Mid: MIDDLE
                module Root: ROOT
                  module Mid: MIDDLE
                    module Leaf: VALUE
                      fn read() -> int:
                        return: 24
                main:
                  print: Root.Mid.Leaf.read()
                """);
        assertTrue(output.contains("24"));
    }

    @Test public void lexicalModuleAliasPreservesSourceIdentity() {
        String output = run(PREFIX + """
                module Outer: OUTER
                  module Public: API
                    fn get() -> int:
                      return: 5
                  fn total() -> int:
                    return: Public.get()
                module Export = Outer.Public
                main:
                  call: assert(Export.get() == 5)
                  print: "PASS module alias"
                """);
        assertTrue(output.contains("PASS module alias"));
    }

    @Test public void cannotAliasPrivateNestedModule() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> run(PREFIX + """
                module Outer: OUTER
                  module Public: API
                    fn get() -> int:
                      return: 1
                  module Private: API
                    fn get() -> int:
                      return: 1
                  fn total() -> int:
                    return: 2
                module Leak = Outer.Private
                main:
                  print: 1
                """));
        assertTrue(ex.getMessage().contains("Private nested module"));
    }
}
