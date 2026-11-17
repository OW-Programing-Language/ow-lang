package dev.oreo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** YAML-style 'name: type' and 'returns: type' syntax, with legacy compatibility. */
public class YamlFunctionSyntaxTest {
    private Ast.Program parse(String text) {
        return new Parser(new Lexer(text, "yaml-signatures.ow").lexAll()).parseProgram();
    }

    private String run(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out, true, StandardCharsets.UTF_8)).run(parse(text));
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void yamlTypedFunctionReturnsArePartOfSignatureNotBody() {
        Ast.FnDecl fn = parse("""
                fn greet(name: string):
                  returns: string
                  return: "Hello " + name
                main:
                  print: greet("OW")
                """).functions.get(0);
        assertEquals("greet", fn.name);
        assertEquals(List.of("name"), fn.params);
        assertEquals("string", fn.paramTypes.get(0));
        assertEquals("string", fn.returnType);
        assertEquals(1, fn.body.size());
        assertTrue(fn.body.get(0) instanceof Ast.ReturnStmt);
    }

    @Test public void yamlFunctionExecutesWithMultipleTypedParameters() {
        assertEquals("5\n", run("""
                fn add(a: int, b: int):
                  returns: int
                  return: a + b
                main:
                  print: add(2, 3)
                """));
    }

    @Test public void yamlStringFunctionExecutesAndCompilesToLLVM() {
        String source = """
                fn greet(name: string):
                  returns: string
                  return: "Hello " + name
                main:
                  print: greet("OW")
                """;
        assertEquals("Hello OW\n", run(source));
        String ir = new LlvmIrGen().generate(parse(source), "yaml_syntax_test");
        assertTrue(ir.contains("define %ow.string @greet(%ow.string %name.arg)"));
        assertTrue(ir.contains("call %ow.string @__ow_native_string_concat("));
    }

    @Test public void yamlArrayReturnSignatureCompilesToLLVM() {
        String ir = new LlvmIrGen().generate(parse("""
                fn make(size: int):
                  returns: array
                  return: array(size, 7)
                main:
                  set values: make(3)
                  print: values[1]
                """), "yaml_array_test");
        assertTrue(ir.contains("define %ow.array @make(i32 %size.arg)"));
    }

    @Test public void yamlClassMethodsStoreReturnTypes() {
        Ast.Program p = parse("""
                class Greeter:
                  fn greet(name: string):
                    returns: string
                    return: "Hello " + name
                main:
                  print: 1
                """);
        Ast.MethodDecl method = p.classes.get(0).methods.get(0);
        assertEquals("greet", method.name);
        assertEquals("string", method.paramTypes.get(0));
        assertEquals("string", method.returnType);
        assertEquals(1, method.body.size());
    }

    @Test public void yamlModuleMethodsStoreReturnTypes() {
        Ast.Program p = parse("""
                module_type API:
                  fn read(index: int):
                    returns: int
                module Store: API
                  fn read(index: int):
                    returns: int
                    return: index
                main:
                  print: Store.read(7)
                """);
        assertEquals("int", p.moduleTypes.get(0).methods.get(0).returnType);
        assertEquals("int", p.modules.get(0).methods.get(0).returnType);
        assertEquals("7\n", run("""
                module_type API:
                  fn read(index: int):
                    returns: int
                module Store: API
                  fn read(index: int):
                    returns: int
                    return: index
                main:
                  print: Store.read(7)
                """));
    }

    @Test public void yamlInterfaceSignatureReturnsAreParsed() {
        Ast.Program p = parse("""
                interface Formatter:
                  fn format(value: string):
                    returns: string
                main:
                  print: 1
                """);
        Ast.MethodSig method = p.interfaces.get(0).methods.get(0);
        assertEquals("string", method.paramTypes.get(0));
        assertEquals("string", method.returnType);
    }

    @Test public void legacyArrowsAndSpaceTypesRemainSupported() {
        Ast.FnDecl fn = parse("""
                fn old(a int, b int) -> int:
                  return: a + b
                main:
                  print: old(2, 3)
                """).functions.get(0);
        assertEquals("int", fn.paramTypes.get(0));
        assertEquals("int", fn.paramTypes.get(1));
        assertEquals("int", fn.returnType);
        assertEquals("5\n", run("""
                fn old(a int, b int) -> int:
                  return: a + b
                main:
                  print: old(2, 3)
                """));
    }

    @Test public void legacyUntypedFunctionsStillWork() {
        assertEquals("42\n", run("""
                fn answer(x):
                  return: x
                main:
                  print: answer(42)
                """));
    }

    @Test public void parserPermitsMixedTypedAndUntypedParameters() {
        Ast.FnDecl fn = parse("""
                fn show(a: int, b string, c):
                  return: c
                main:
                  print: 0
                """).functions.get(0);
        assertEquals("int", fn.paramTypes.get(0));
        assertEquals("string", fn.paramTypes.get(1));
        assertNull(fn.paramTypes.get(2));
    }

    @Test public void annotationsContinueToWorkWithYamlTypes() {
        Ast.FnDecl fn = parse("""
                @Test
                fn example():
                  returns: int
                  return: 1
                """).functions.get(0);
        assertEquals("int", fn.returnType);
        assertEquals(List.of("Test"), fn.annotations);
    }

    @Test public void duplicateReturnDeclarationsAreRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                fn invalid() -> int:
                  returns: string
                  return: 1
                """));
        assertTrue(error.getMessage().contains("both '->' and 'returns:'"));
    }

    @Test public void duplicateReturnDeclarationsInInterfaceAreRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                interface API:
                  fn calculate() -> int:
                    returns: int
                """));
        assertTrue(error.getMessage().contains("both '->' and 'returns:'"));
    }

    @Test public void missingYamlParameterTypeIsRejected() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                fn invalid(value:):
                  return: value
                """));
        assertTrue(error.getMessage().contains("Expected IDENT"));
    }

    @Test public void returnsMustBeFirstInBody() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse("""
                fn invalid():
                  print: 1
                  returns: int
                  return: 1
                """));
        assertTrue(error.getMessage().contains("returns"));
    }

    @Test public void yamlReturnsWithoutReturnValueIsNotAnExecutableReturn() {
        Ast.FnDecl fn = parse("""
                fn noop():
                  returns: int
                main:
                  print: noop()
                """).functions.get(0);
        assertEquals("int", fn.returnType);
        assertTrue(fn.body.isEmpty());
    }

    @Test public void legacySignatureOnlyInterfaceAndModuleTypeRemainSupported() {
        Ast.Program p = parse("""
                interface API:
                  fn get(a int) -> int
                module_type STORE:
                  fn size() -> int
                main:
                  print: 0
                """);
        assertEquals("int", p.interfaces.get(0).methods.get(0).returnType);
        assertEquals("int", p.moduleTypes.get(0).methods.get(0).returnType);
    }
}
