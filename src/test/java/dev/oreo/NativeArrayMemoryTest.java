package dev.oreo;

import org.junit.Test;
import static org.junit.Assert.*;

/** LLVM ownership invariants for integer arrays (not a full tracing GC). */
public class NativeArrayMemoryTest {
    private String ir(String source) {
        Ast.Program program = new Parser(new Lexer(source, "native-memory.ow").lexAll())
                .parseProgram();
        return new LlvmIrGen().generate(program, "memory_test");
    }

    private static int count(String source, String fragment) {
        return source.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    @Test public void arrayAbiContainsRefsAndReleaseFreesBothAllocations() {
        String llvm = ir("""
                main:
                  set a: [1, 2]
                  print: a[1]
                """);
        assertTrue(llvm.contains("%ow.array = type { i32, i32*, i32* }"));
        assertTrue(llvm.contains("declare void @free(i8*)"));
        assertTrue(llvm.contains("define void @__ow_native_array_release"));
        assertTrue(llvm.contains("define void @__ow_native_array_retain"));
        assertTrue(llvm.contains("call void @free(i8* %rawData)"));
        assertTrue(llvm.contains("call void @free(i8* %rawRefs)"));
        assertTrue(llvm.contains("store i32 1, i32*"));
        assertTrue(llvm.contains("insertvalue %ow.array"));
    }

    @Test public void aliasesRetainAndBothSlotsAreReleased() {
        String llvm = ir("""
                main:
                  set original: [1, 2]
                  set alias: original
                  set original: [3, 4]
                  print: alias[0]
                """);
        assertEquals(1, count(llvm, "call void @__ow_native_array_retain(%ow.array"));
        // Reassignment releases a prior value, and both local slots are
        // released by the function epilogue.
        assertTrue(count(llvm, "call void @__ow_native_array_release(%ow.array") >= 5);
    }

    @Test public void selfAssignmentRetainsBeforeReleasing() {
        String llvm = ir("""
                main:
                  set x: [1]
                  set x: x
                  print: x[0]
                """);
        int retain = llvm.indexOf("call void @__ow_native_array_retain(%ow.array");
        // The initial assignment may have already released null. Search from
        // the retain instruction for the paired old-value release.
        int releaseAfter = llvm.indexOf("call void @__ow_native_array_release(%ow.array", retain);
        assertTrue(retain > 0);
        assertTrue(releaseAfter > retain);
    }

    @Test public void newArraySlotsStartAsNullEvenInConditionalBlocks() {
        String llvm = ir("""
                main:
                  if: false
                    set never: [9]
                  print: 10
                """);
        int entrySlot = llvm.indexOf("%never = alloca %ow.array");
        int branch = llvm.indexOf("br i1", llvm.indexOf("define i32 @main()"));
        assertTrue(entrySlot > 0);
        assertTrue(branch > entrySlot);
        assertTrue(llvm.contains("store %ow.array zeroinitializer, %ow.array* %never"));
    }

    @Test public void temporaryArrayInLenAndIndexIsReleased() {
        String llvm = ir("""
                main:
                  print: len(array(2, 7))
                  print: [10, 20][1]
                """);
        // Each use consumes the temporary, leaving no dangling heap owner.
        assertTrue(count(llvm, "call void @__ow_native_array_release(%ow.array") >= 2);
    }

    @Test public void ignoredArrayCallReleasesTemporary() {
        String llvm = ir("""
                main:
                  call: array(2, 9)
                """);
        assertTrue(llvm.contains("call void @__ow_native_array_release(%ow.array"));
    }

    @Test public void returnReleasesLocalsBeforeExit() {
        String llvm = ir("""
                fn consume():
                  set data: array(2, 7)
                  return: data[1]
                main:
                  print: consume()
                """);
        int functionStart = llvm.indexOf("define i32 @consume(");
        int ret = llvm.indexOf("ret i32 %", functionStart);
        int release = llvm.indexOf("call void @__ow_native_array_release(%ow.array", functionStart);
        assertTrue(release > 0 && release < ret);
    }

    @Test public void userFunctionsCannotOverrideRuntimeHelpers() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn __ow_native_array_release():
                  return: 1
                main:
                  print: 1
                """));
        assertTrue(e.getMessage().contains("reserved"));
    }
}
