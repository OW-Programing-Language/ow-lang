package dev.oreo;

import org.junit.Test;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** LLVM string-array ownership and native ABI regression tests. */
public class NativeStringArrayLlvmTest {
    private String ir(String source) {
        Ast.Program ast = new Parser(new Lexer(source, "native-string-arrays.ow")
                .lexAll()).parseProgram();
        return new LlvmIrGen().generate(ast, "native_string_arrays");
    }
    private int count(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test public void stringArrayLiteralAndIndexAreLowered() {
        String ll = ir("""
                main:
                  set names: ["Alice", "Bob", "Charlie"]
                  print: names[1]
                  print: len(names)
                """);
        assertTrue(ll.contains("%ow.strarray = type { i32, %ow.string*, i32* }"));
        assertTrue(ll.contains("call %ow.strarray @__ow_native_strarray_new(i32 3)"));
        assertTrue(ll.contains("getelementptr %ow.string, %ow.string*"));
        assertTrue(ll.contains("load %ow.string, %ow.string*"));
        assertTrue(ll.contains("extractvalue %ow.strarray"));
    }

    @Test public void nativeStringArrayReleasesEachElementAtLastOwner() {
        String ll = ir("""
                main:
                  set names: ["hello" + " OW", "bye"]
                  set alias: names
                  set names: ["replaced"]
                  print: alias[0]
                """);
        assertTrue(ll.contains("define void @__ow_native_strarray_release("));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string %string)"));
        assertTrue(ll.contains("call void @free(i8* %rawData)"));
        assertTrue(ll.contains("call void @free(i8* %rawRefs)"));
        assertTrue(ll.contains("call void @__ow_native_strarray_retain(%ow.strarray"));
        assertTrue(ll.contains("call void @__ow_native_strarray_release(%ow.strarray"));
    }

    @Test public void assigningStringElementRetainsBeforeReleasingOldValue() {
        String ll = ir("""
                main:
                  set names: ["a" + "b"]
                  set names[0]: names[0]
                  print: names[0]
                """);
        int main = ll.indexOf("define i32 @main()");
        int idx = ll.indexOf("array.index.ok.", main);
        int retain = ll.indexOf("call void @__ow_native_string_retain(%ow.string", idx);
        int release = ll.indexOf("call void @__ow_native_string_release(%ow.string", idx);
        assertTrue(idx > main && retain > idx && release > retain);
    }

    @Test public void temporaryStringArrayIndexKeepsChildAlive() {
        String ll = ir("""
                main:
                  print: ["a" + "b", "c"][0]
                """);
        int main = ll.indexOf("define i32 @main()");
        int retain = ll.indexOf("call void @__ow_native_string_retain(%ow.string", main);
        int releaseArray = ll.indexOf("call void @__ow_native_strarray_release(%ow.strarray", retain);
        int releaseChild = ll.indexOf("call void @__ow_native_string_release(%ow.string", releaseArray);
        assertTrue(retain > main);
        assertTrue(releaseArray > retain);
        assertTrue(releaseChild > releaseArray);
    }

    @Test public void dynamicStringFillRetainedForEachElement() {
        String ll = ir("""
                fn text():
                  returns: string
                  return: "A" + "B"
                main:
                  set names: array(3, text())
                  print: names[1]
                """);
        int main = ll.indexOf("define i32 @main()");
        String body = ll.substring(main);
        assertEquals(1, count(body, "call %ow.string @text()"));
        assertTrue(body.contains("strarray.fill.body."));
        assertTrue(body.contains("call void @__ow_native_string_retain(%ow.string"));
        assertTrue(body.contains("call void @__ow_native_string_release(%ow.string"));
    }

    @Test public void explicitStringArrayFunctionABIWorks() {
        String ll = ir("""
                fn create(count: int):
                  returns: string_array
                  return: array(count, "a" + "b")
                fn borrow(items: string_array):
                  returns: string
                  return: items[0]
                fn identity(items: string_array):
                  returns: string_array
                  return: items
                main:
                  set values: identity(create(2))
                  print: borrow(values)
                """);
        assertTrue(ll.contains("define %ow.strarray @create(i32 %count.arg)"));
        assertTrue(ll.contains("define %ow.string @borrow(%ow.strarray %items.arg)"));
        assertTrue(ll.contains("define %ow.strarray @identity(%ow.strarray %items.arg)"));
        assertTrue(ll.contains("call %ow.strarray @identity(%ow.strarray"));
        assertTrue(ll.contains("call void @__ow_native_strarray_retain(%ow.strarray"));
    }

    @Test public void emptyDynamicStringArrayAndBoundsChecks() {
        String ll = ir("""
                main:
                  set empty: array(0, "")
                  print: len(empty)
                  set names: ["x"]
                  print: names[0]
                """);
        assertTrue(ll.contains("call %ow.strarray @__ow_native_strarray_new(i32 0)"));
        assertTrue(ll.contains("array.index.fail."));
        assertTrue(ll.contains("icmp sge i32"));
        assertTrue(ll.contains("icmp slt i32"));
        assertTrue(ll.contains("call void @abort()"));
    }

    @Test public void mixedArraysAreRejectedInsteadOfMiscompiled() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set invalid: ["a", 1]
                """));
        assertTrue(error.getMessage().contains("mixed integer/string array literal"));
    }

    @Test public void mixingIntAndStringArrayMutationFails() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set names: ["a"]
                  set names[0]: 42
                """));
        assertTrue(error.getMessage().contains("string arrays require string elements"));
    }

    @Test public void incompatibleStringArrayParameterFailsEarly() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ir("""
                fn consume(items: string_array):
                  returns: int
                  return: len(items)
                main:
                  print: consume([1, 2])
                """));
        assertTrue(error.getMessage().contains("expects %ow.strarray"));
    }

    @Test public void integerArraysRemainInPreviousABI() {
        String ll = ir("""
                main:
                  set values: array(3, 7)
                  set values[1]: 9
                  print: values[1]
                """);
        assertTrue(ll.contains("call i8* @calloc(i64"));
        assertTrue(ll.contains("%ow.array = type { i32, i32*, i32* }"));
        assertTrue(ll.contains("store i32 9, i32*"));
    }
}
