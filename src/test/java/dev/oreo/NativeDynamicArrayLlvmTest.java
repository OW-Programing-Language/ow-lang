package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** LLVM lowering of array(size, fill), including integer-only type restrictions. */
public class NativeDynamicArrayLlvmTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "dynamic-native-arrays.ow")
                .lexAll()).parseProgram();
        return new LlvmIrGen().generate(program, "dynamic_arrays_test");
    }

    @Test public void arrayBuiltinCompilesAsCheckedAllocationAndFillLoop() {
        String llvm = ir("""
                main:
                  set size: 3
                  set items: array(size, 7)
                  print: len(items)
                  print: items[1]
                """);
        assertTrue(llvm.contains("array.size.fail."));
        assertTrue(llvm.contains("icmp sge i32"));
        assertTrue(llvm.contains("select i1"));
        assertTrue(llvm.contains("zext i32"));
        assertTrue(llvm.contains("call i8* @calloc(i64"));
        assertTrue(llvm.contains("phi i32"));
        assertTrue(llvm.contains("array.fill.body."));
        assertTrue(llvm.contains("store i32 7, i32*"));
        assertTrue(llvm.contains("insertvalue %ow.array"));
    }

    @Test public void emptyDynamicArraysStillHaveValidAllocation() {
        String llvm = ir("""
                main:
                  set empty: array(0, 123)
                  print: len(empty)
                """);
        assertTrue(llvm.contains("select i1"));
        assertTrue(llvm.contains("i32 1, i32 0"));
        assertTrue(llvm.contains("array.fill.cond."));
    }

    @Test public void fillFunctionIsEvaluatedExactlyOnceInIr() {
        String llvm = ir("""
                fn compute():
                  return: 21
                main:
                  set items: array(4, compute())
                  print: items[2]
                """);
        assertEquals(1, llvm.split("call i32 @compute\\(", -1).length - 1);
    }

    @Test public void incorrectArrayArityRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set items: array(2)
                """));
        assertTrue(e.getMessage().contains("size and fill"));
    }

    @Test public void invalidDynamicSizeTypeRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set items: array("nope", 3)
                """));
        assertTrue(e.getMessage().contains("size must be an integer"));
    }

    @Test public void nestedDynamicFillTypeRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set items: array(3, [1])
                """));
        assertTrue(e.getMessage().contains("array fill must be int or string"));
    }

    @Test public void dynamicStringFillUsesStringArrayABI() {
        String llvm = ir("""
                main:
                  set names: array(3, "x")
                  print: names[0]
                """);
        assertTrue(llvm.contains("call %ow.strarray @__ow_native_strarray_new(i32 3)"));
    }
}
