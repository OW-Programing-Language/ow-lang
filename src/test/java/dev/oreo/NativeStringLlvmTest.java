package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** Native immutable UTF-8 string ABI, contents comparison, and OW length semantics. */
public class NativeStringLlvmTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "native-strings.ow")
                .lexAll()).parseProgram();
        return new LlvmIrGen().generate(program, "native_strings_test");
    }

    @Test public void typedStringArgumentsAndReturnsUsePointerAbi() {
        String llvm = ir("""
                fn identity(text string) -> string:
                  return: text
                main:
                  print: identity("bonjour")
                """);
        assertTrue(llvm.contains("define i8* @identity(i8* %text.arg)"));
        assertTrue(llvm.contains("alloca i8*"));
        assertTrue(llvm.contains("ret i8*"));
        assertTrue(llvm.contains("call i8* @identity(i8*"));
    }

    @Test public void comparisonsUseContentNotPointerIdentity() {
        String llvm = ir("""
                main:
                  if: "abc" == "abc"
                    print: 1
                  if: "abc" != "xyz"
                    print: 2
                """);
        assertTrue(llvm.contains("declare i32 @strcmp(i8*, i8*)"));
        assertEquals(2, llvm.split("call i32 @strcmp\\(", -1).length - 1);
        assertTrue(llvm.contains(" = icmp eq i32 "));
        assertTrue(llvm.contains(" = icmp ne i32 "));
    }

    @Test public void nativeLenCountsUtf16UnitsRatherThanUtf8Bytes() {
        String llvm = ir("""
                main:
                  print: len("été")
                  print: len("hé🙂")
                """);
        assertTrue(llvm.contains("define i32 @__ow_native_string_utf16len(i8* %text)"));
        assertEquals(2, llvm.split("call i32 @__ow_native_string_utf16len\\(", -1).length - 1);
        assertTrue(llvm.contains("select i1 %fourByte, i32 2, i32 1"));
        assertTrue(llvm.contains("select i1 %continuation, i32 0"));
    }

    @Test public void standaloneStringPrintingStillWorks() {
        String llvm = ir("""
                main:
                  set hello: "hello"
                  print: hello
                """);
        assertTrue(llvm.contains("call i32 (i8*, ...) @printf(i8*"));
        assertTrue(llvm.contains("alloca i8*"));
    }

    @Test public void stringOrderingFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  if: "a" < "b"
                    print: 1
                """));
        assertTrue(e.getMessage().contains("only == and !="));
    }

    @Test public void mixedStringAndNumberComparisonFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  if: "42" == 42
                    print: 1
                """));
        assertTrue(e.getMessage().contains("two integers or two strings"));
    }

    @Test public void mismatchedStringArgumentFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn identity(text string) -> string:
                  return: text
                main:
                  print: identity(42)
                """));
        assertTrue(e.getMessage().contains("expects i8*"));
    }

    @Test public void wrongStringReturnFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn invalid() -> string:
                  return: 42
                main:
                  print: 0
                """));
        assertTrue(e.getMessage().contains("expected return type i8*"));
    }

    @Test public void missingFinalStringReturnFailsAtCompileTime() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn invalid() -> string:
                  print: "hi"
                main:
                  print: 0
                """));
        assertTrue(e.getMessage().contains("must end with an explicit return"));
    }

    @Test public void stringPlusIsStillExplicitlyUnsupported() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  print: "a" + "b"
                """));
        assertTrue(e.getMessage().contains("arithmetic requires integers"));
    }
}
