package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** First incremental LLVM array backend: heap-backed int arrays with checked access. */
public class NativeArrayLlvmTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "native-arrays.ow").lexAll())
                .parseProgram();
        return new LlvmIrGen().generate(program, "arrays_test");
    }

    @Test public void integerArrayLiteralsAndLenAreLowered() {
        String llvm = ir("""
                main:
                  set numbers: [10, 20, 30]
                  print: len(numbers)
                  print: numbers[1]
                """);
        assertTrue(llvm.contains("%ow.array = type { i32, i32*, i32* }"));
        assertTrue(llvm.contains("call i8* @calloc(i64 3, i64 4)"));
        assertTrue(llvm.contains("insertvalue %ow.array"));
        assertTrue(llvm.contains("extractvalue %ow.array"));
        assertTrue(llvm.contains("call i32 (i8*, ...) @printf"));
    }

    @Test public void mutationAndAliasesShareBackingArray() {
        String llvm = ir("""
                main:
                  set numbers: [10, 20]
                  set alias: numbers
                  set numbers[1]: 99
                  print: alias[1]
                """);
        assertTrue(llvm.contains("alloca %ow.array"));
        assertTrue(llvm.contains("store %ow.array"));
        assertTrue(llvm.contains("load %ow.array"));
        assertTrue(llvm.contains("store i32 99, i32*"));
    }

    @Test public void indexingChecksBothSidesOfBoundsBeforeAddressing() {
        String llvm = ir("""
                main:
                  set values: [4, 5]
                  print: values[0]
                """);
        assertTrue(llvm.contains("icmp sge i32"));
        assertTrue(llvm.contains("icmp slt i32"));
        assertTrue(llvm.contains(" = and i1 "));
        assertTrue(llvm.contains("array.index.fail."));
        assertTrue(llvm.contains("call void @abort()"));
        assertTrue(llvm.contains("unreachable"));
    }

    @Test public void emptyArraysDoNotDependOnCallocZeroSize() {
        String llvm = ir("""
                main:
                  set nothing: []
                  print: len(nothing)
                """);
        assertTrue(llvm.contains("call i8* @calloc(i64 1, i64 4)"));
        assertTrue(llvm.contains("insertvalue %ow.array undef, i32 0, 0"));
    }

    @Test public void stringArraysFailRatherThanBeingMiscompiled() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set names: ["one", "two"]
                """));
        assertTrue(e.getMessage().contains("only integers"));
    }

    @Test public void stringLenUsesUtf16CodeUnitsRatherThanArrayHeader() {
        String llvm = ir("""
                main:
                  print: len("hello")
                """);
        assertTrue(llvm.contains("call i32 @__ow_native_string_utf16len(i8*"));
        assertFalse(llvm.contains("extractvalue %ow.array %t"));
    }

    @Test public void indexingNonArrayFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set number: 12
                  print: number[0]
                """));
        assertTrue(e.getMessage().contains("requires an integer array"));
    }
}
