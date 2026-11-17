package dev.oreo;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Dynamic string concatenation and reference-counted LLVM ownership tests. */
public class NativeDynamicStringLlvmTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "dynamic-strings.ow")
                .lexAll()).parseProgram();
        return new LlvmIrGen().generate(program, "native_dynamic_strings");
    }

    private static int calls(String ll, String fragment) {
        return ll.split(Pattern.quote(fragment), -1).length - 1;
    }

    @Test public void concatenationGeneratesHeapBackedReferenceCountedString() {
        String ll = ir("""
                main:
                  print: "Hello " + "OW"
                """);
        assertTrue(ll.contains("%ow.string = type { i8*, i32* }"));
        assertTrue(ll.contains("define %ow.string @__ow_native_string_concat"));
        assertTrue(ll.contains("call i64 @strlen(i8* %ltext)"));
        assertTrue(ll.contains("call i8* @memcpy("));
        assertTrue(ll.contains("call i8* @calloc(i64 %bytes, i64 1)"));
        assertTrue(ll.contains("store i32 1, i32* %refs"));
        assertTrue(ll.contains("call %ow.string @__ow_native_string_concat"));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string"));
    }

    @Test public void bothBuffersAreFreedWhenLastReferenceIsReleased() {
        String ll = ir("""
                main:
                  set text: "O" + "W"
                  set text: "new" + " value"
                """);
        assertTrue(ll.contains("define void @__ow_native_string_release"));
        assertTrue(ll.contains("call void @free(i8* %data)"));
        assertTrue(ll.contains("call void @free(i8* %refsAsBytes)"));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string"));
        assertTrue(ll.contains("call void @__ow_native_string_retain(%ow.string") ||
                   ll.contains("store %ow.string"));
    }

    @Test public void dynamicStringAliasesRetainBeforeReassignment() {
        String ll = ir("""
                main:
                  set original: "hello" + " OW"
                  set alias: original
                  set original: "bye" + " OW"
                  print: alias
                """);
        int main = ll.indexOf("define i32 @main()");
        int retain = ll.indexOf("call void @__ow_native_string_retain(%ow.string", main);
        int releaseAfter = ll.indexOf("call void @__ow_native_string_release(%ow.string", retain);
        assertTrue(retain > main);
        assertTrue(releaseAfter > retain);
        assertTrue(ll.contains("store %ow.string zeroinitializer"));
    }

    @Test public void typedFunctionCallsTransferStringOwnership() {
        String ll = ir("""
                fn join(left string, right string) -> string:
                  return: left + right
                main:
                  set text: join("Hello ", "OW")
                  print: text
                """);
        assertTrue(ll.contains("define %ow.string @join(%ow.string %left.arg, %ow.string %right.arg)"));
        assertTrue(ll.contains("call %ow.string @join(%ow.string"));
        assertTrue(ll.contains("ret %ow.string"));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string"));
    }

    @Test public void temporaryStringLenAndComparisonAreReleased() {
        String ll = ir("""
                main:
                  print: len("hé" + "🙂")
                  if: ("a" + "b") == "ab"
                    print: 1
                """);
        int main = ll.indexOf("define i32 @main()");
        assertTrue(calls(ll.substring(main), "call void @__ow_native_string_release(%ow.string") >= 2);
    }

    @Test public void returningBorrowedStringRetainsBeforeParameterCleanup() {
        String ll = ir("""
                fn identity(text string) -> string:
                  return: text
                main:
                  print: identity("hi" + "!")
                """);
        int fn = ll.indexOf("define %ow.string @identity(");
        int retain = ll.indexOf("call void @__ow_native_string_retain(%ow.string", fn);
        int release = ll.indexOf("call void @__ow_native_string_release(%ow.string", fn);
        int ret = ll.indexOf("ret %ow.string %", fn);
        assertTrue(retain > fn && retain < release && release < ret);
    }

    @Test public void helperFunctionsRejectOverflowAndFailedAllocations() {
        String ll = ir("""
                main:
                  print: "a" + "b"
                """);
        assertTrue(ll.contains("%valid = and i1 %lenOk, %sizeOk"));
        assertTrue(ll.contains("%dataValid = icmp ne i8* %data, null"));
        assertTrue(ll.contains("%refsValid = icmp ne i8* %refsRaw, null"));
        assertTrue(ll.contains("call void @abort()"));
    }

    @Test public void nestedConcatenationTransfersOwnedTemporaries() {
        String ll = ir("""
                main:
                  set text: ("a" + "b") + ("c" + "d")
                  print: text
                """);
        int main = ll.indexOf("define i32 @main()");
        assertEquals(3, calls(ll.substring(main), "call %ow.string @__ow_native_string_concat"));
        assertTrue(calls(ll.substring(main), "call void @__ow_native_string_release(%ow.string") >= 4);
    }
}
