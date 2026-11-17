package dev.oreo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Type checking and owned-array calling conventions for native functions. */
public class NativeArrayFunctionLlvmTest {
    private Ast.Program parse(String source) {
        return new Parser(new Lexer(source, "native-array-functions.ow").lexAll()).parseProgram();
    }

    private String ir(String source) {
        return new LlvmIrGen().generate(parse(source), "native_array_function_test");
    }

    @Test public void parserPreservesTypedTopLevelSignatures() {
        Ast.FnDecl fn = parse("""
                fn keep(values array, index int) -> array:
                  return: values
                main:
                  print: 0
                """).functions.get(0);
        assertEquals(List.of("values", "index"), fn.params);
        assertEquals("array", fn.paramTypes.get(0));
        assertEquals("int", fn.paramTypes.get(1));
        assertEquals("array", fn.returnType);
    }

    @Test public void oldUntypedFunctionSyntaxRemainsSupported() {
        Ast.FnDecl fn = parse("""
                fn add(x, y):
                  return: x + y
                main:
                  print: add(2, 3)
                """).functions.get(0);
        assertNull(fn.paramTypes.get(0));
        assertNull(fn.paramTypes.get(1));
        assertNull(fn.returnType);
        assertTrue(ir("""
                fn add(x, y):
                  return: x + y
                main:
                  print: add(2, 3)
                """).contains("define i32 @add(i32 %x.arg, i32 %y.arg)"));
    }

    @Test public void nativeFunctionsReceiveAndReturnArrays() {
        String ll = ir("""
                fn mirror(values array) -> array:
                  return: values
                fn make(size int) -> array:
                  return: array(size, 7)
                main:
                  set list: mirror(make(3))
                  print: list[0]
                """);
        assertTrue(ll.contains("define %ow.array @mirror(%ow.array %values.arg)"));
        assertTrue(ll.contains("define %ow.array @make(i32 %size.arg)"));
        assertTrue(ll.contains("call %ow.array @make(i32 3)"));
        assertTrue(ll.contains("call %ow.array @mirror(%ow.array"));
        assertTrue(ll.contains("ret %ow.array"));
        assertTrue(ll.contains("call void @__ow_native_array_retain(%ow.array"));
        assertTrue(ll.contains("call void @__ow_native_array_release(%ow.array"));
    }

    @Test public void borrowedArrayArgumentRetainedBeforeInvocation() {
        String ll = ir("""
                fn length(values array) -> int:
                  return: len(values)
                main:
                  set list: [4, 5]
                  print: length(list)
                  print: list[0]
                """);
        int main = ll.indexOf("define i32 @main()");
        int retain = ll.indexOf("call void @__ow_native_array_retain(%ow.array", main);
        int invoke = ll.indexOf("call i32 @length(", main);
        assertTrue(retain > main && invoke > retain);
    }

    @Test public void ownedFunctionResultMayBeUnusedSafely() {
        String ll = ir("""
                fn factory() -> array:
                  return: [1, 2]
                main:
                  call: factory()
                """);
        int main = ll.indexOf("define i32 @main()");
        int invoke = ll.indexOf("call %ow.array @factory()", main);
        int release = ll.indexOf("call void @__ow_native_array_release(%ow.array", main);
        assertTrue(invoke > main && release > invoke);
    }

    @Test public void wrongArrayArgumentRejectedBeforeLLVMEmission() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn inspect(values array) -> int:
                  return: len(values)
                main:
                  print: inspect(17)
                """));
        assertTrue(e.getMessage().contains("expects %ow.array"));
    }

    @Test public void untypedFunctionArgumentDoesNotSilentlyBecomeAnArray() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn passthrough(values):
                  return: 1
                main:
                  set a: [3]
                  print: passthrough(a)
                """));
        assertTrue(e.getMessage().contains("expects i32"));
    }

    @Test public void wrongNativeReturnTypeRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn bad() -> array:
                  return: 1
                main:
                  print: 0
                """));
        assertTrue(e.getMessage().contains("expected return type %ow.array"));
    }

    @Test public void arrayReturningFunctionCannotFallThrough() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn missing() -> array:
                  set a: [1, 2]
                main:
                  print: 1
                """));
        assertTrue(e.getMessage().contains("must end with an explicit return"));
    }

    @Test public void unsupportedFunctionTypeFailsExplicitly() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn unsupported(value string) -> int:
                  return: 1
                main:
                  print: 0
                """));
        assertTrue(e.getMessage().contains("unsupported native function type"));
    }

    @Test public void arityMismatchProducesClearCompilerError() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn make(size int, fill int) -> array:
                  return: array(size, fill)
                main:
                  set a: make(2)
                """));
        assertTrue(e.getMessage().contains("expects 2 arguments"));
    }

    @Test public void moduleLoweringRetainsTopLevelFunctionTypes() {
        Ast.Program program = parse("""
                fn make(size int) -> array:
                  return: array(size, 3)
                main:
                  set items: make(2)
                  print: items[0]
                """);
        Ast.Program lowered = NativeModuleLlvmLowerer.lower(program);
        assertEquals("array", lowered.functions.get(0).returnType);
        assertEquals("int", lowered.functions.get(0).paramTypes.get(0));
    }
}
