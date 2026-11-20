package dev.oreo;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.Assert.*;

public class ModuleDiscoveryTest {
    private String run(String source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(
                new Parser(new Lexer(source, "discovery.ow").lexAll()).parseProgram());
        return out.toString();
    }

    @Test public void discoversPublicModulesAliasesAndInstantiatedFunctorsOnce() {
        String output = run("""
                module_type SERVICE:
                  fn name() -> string
                module Alpha: SERVICE
                  fn name() -> string:
                    return: "alpha"
                module Domain.Beta: SERVICE
                  fn name() -> string:
                    return: "beta"
                module Alias = Alpha
                functor MakeService(Source: SERVICE) -> SERVICE:
                  fn name() -> string:
                    return: "derived"
                main:
                  set derived SERVICE: MakeService(Alpha)
                  set discovered: module_scan("SERVICE")
                  call: assert(len(discovered) == 3, "three distinct modules")
                  call: assert(discovered[0].name() == "alpha", "sorted alpha")
                  call: assert(discovered[1].name() == "beta", "qualified beta")
                  call: assert(discovered[2].name() == "derived", "functor instance")
                  print: "PASS module scan"
                """);
        assertTrue(output, output.contains("PASS module scan"));
    }

    @Test public void neverLeaksPrivateLexicalModules() {
        String output = run("""
                module_type SERVICE:
                  fn name() -> string
                module_type OUTER:
                  module Public: SERVICE
                module Holder: OUTER
                  module Public: SERVICE
                    fn name() -> string:
                      return: "public"
                  module Private: SERVICE
                    fn name() -> string:
                      return: "secret"
                main:
                  set discovered: module_scan("SERVICE")
                  call: assert(len(discovered) == 1, "only exported children")
                  call: assert(discovered[0].name() == "public")
                  print: "PASS private module hidden"
                """);
        assertTrue(output, output.contains("PASS private module hidden"));
    }

    @Test public void rejectsUnknownSignatureAndInvalidArgument() {
        RuntimeException unknown = assertThrows(RuntimeException.class,
                () -> run("main:\n  set found: module_scan(\"MISSING\")\n"));
        assertTrue(unknown.getMessage(), unknown.getMessage().contains("Unknown module type MISSING"));
        RuntimeException invalid = assertThrows(RuntimeException.class,
                () -> run("main:\n  set found: module_scan(7)\n"));
        assertTrue(invalid.getMessage(), invalid.getMessage().contains("nonempty module_type name"));
    }

    @Test public void mismatchedSignatureReturnsEmptyWithoutExecutingMembers() {
        String output = run("""
                module_type ONE:
                  fn value() -> int
                module_type TWO:
                  fn name() -> string
                module Only: ONE
                  fn value() -> int:
                    return: 9
                main:
                  set found: module_scan("TWO")
                  call: assert(len(found) == 0, "unrelated modules excluded")
                  print: "PASS no match"
                """);
        assertTrue(output, output.contains("PASS no match"));
    }
}
