package dev.oreo;

import org.junit.Test;
import static org.junit.Assert.*;

/** Runtime ABI and ARC checks for native string-keyed/string-valued dictionaries. */
public class NativeStringDictionaryLlvmTest {
    private String ir(String source) {
        Ast.Program ast = new Parser(new Lexer(source, "native-string-map.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(ast, "stringmap_test");
    }

    @Test public void stringMapLiteralSelectsDistinctNativeType() {
        String llvm = ir("""
                main:
                  set m: { "Blaze": "mob/blaze", Zombie: "mob/zombie" }
                  print: m["Blaze"]
                """);
        assertTrue(llvm.contains("%ow.strmap.entry = type { %ow.string, %ow.string, %ow.strmap.entry*, %ow.strmap.entry*, %ow.strmap.entry* }"));
        assertTrue(llvm.contains("%ow.strmap = type { %ow.strmap.entry**, i32, i32, i32, %ow.strmap.entry*, %ow.strmap.entry* }"));
        assertTrue(llvm.contains("call %ow.strmap* @__ow_native_strmap_new()"));
        assertTrue(llvm.contains("call void @__ow_native_strmap_set(%ow.strmap*"));
        assertTrue(llvm.contains("call %ow.string @__ow_native_strmap_get(%ow.strmap*"));
    }

    @Test public void stringMapIndexResultIsOwned() {
        String llvm = ir("""
                main:
                  print: { a: "ab" + "cd" }["a"]
                """);
        int main = llvm.indexOf("define i32 @main()");
        assertTrue(llvm.indexOf("call %ow.string @__ow_native_strmap_get(", main) > main);
        assertTrue(llvm.indexOf("call void @__ow_native_strmap_release(", main) > main);
        assertTrue(llvm.indexOf("call void @__ow_native_string_release(", main) > main);
        assertTrue(llvm.contains("call void @__ow_native_string_retain(%ow.string %value)"));
    }

    @Test public void stringMapReleaseFreesKeysValuesAndNodes() {
        String llvm = ir("""
                main:
                  set original: { k: "a" + "b" }
                  set alias: original
                  set original: { k: "new" }
                  print: alias["k"]
                """);
        assertTrue(llvm.contains("call void @__ow_native_strmap_retain(%ow.strmap*"));
        assertTrue(llvm.contains("define void @__ow_native_strmap_release("));
        assertTrue(llvm.contains("call void @__ow_native_string_release(%ow.string %key)"));
        assertTrue(llvm.contains("call void @__ow_native_string_release(%ow.string %value)"));
        assertTrue(llvm.contains("call void @free(i8* %rawNode)"));
    }

    @Test public void replacingMapValueReleasesOldValue() {
        String llvm = ir("""
                main:
                  set m: { key: "a" + "b" }
                  set m["key"]: m["key"]
                """);
        assertTrue(llvm.contains("store %ow.string %value, %ow.string* %valuePtr"));
        assertTrue(llvm.contains("call void @__ow_native_string_release(%ow.string %prior)"));
        assertTrue(llvm.contains("call void @__ow_native_string_release(%ow.string %val)"));
    }

    @Test public void mapGetFallbackRemainsLazyAndReturnsOwnedString() {
        String llvm = ir("""
                fn fallback():
                  returns: string
                  return: "not" + "used"
                main:
                  set m: { key: "value" }
                  print: map_get(m, "key", fallback())
                """);
        int main = llvm.indexOf("define i32 @main()");
        int absent = llvm.indexOf("strmap.get.absent.", main);
        int fallback = llvm.indexOf("call %ow.string @fallback()", absent);
        assertTrue(absent > main && fallback > absent);
        assertTrue(llvm.contains("store %ow.string"));
        assertTrue(llvm.contains("call void @__ow_native_string_retain(%ow.string"));
    }

    @Test public void sizeHasSetDeleteWorkWithStringMaps() {
        String llvm = ir("""
                main:
                  set m: { a: "x" }
                  print: map_has(m, "a")
                  set m: map_set(m, "b", "y")
                  set m: map_delete(m, "a")
                  print: map_size(m)
                """);
        assertTrue(llvm.contains("call i1 @__ow_native_strmap_has("));
        assertTrue(llvm.contains("call i32 @__ow_native_strmap_size("));
        assertTrue(llvm.contains("call void @__ow_native_strmap_delete("));
    }

    @Test public void typedFunctionBoundariesSupportStringMap() {
        String llvm = ir("""
                fn create():
                  returns: string_map
                  return: { name: "OW" }
                fn value(m: string_map):
                  returns: string
                  return: m["name"]
                main:
                  print: value(create())
                """);
        assertTrue(llvm.contains("define %ow.strmap* @create()"));
        assertTrue(llvm.contains("define %ow.string @value(%ow.strmap* %m.arg)"));
        assertTrue(llvm.contains("call %ow.strmap* @create()"));
    }

    @Test public void distinctIntegerMapsRemainSupported() {
        String llvm = ir("""
                main:
                  set m: { a: 1 }
                  set m["a"]: 7
                  print: m["a"]
                """);
        assertTrue(llvm.contains("%ow.intmap.entry = type { %ow.string, i32, %ow.intmap.entry*, %ow.intmap.entry*, %ow.intmap.entry* }"));
        assertTrue(llvm.contains("call i32 @__ow_native_intmap_get("));
    }

    @Test public void mixedValueTypesSelectTaggedMap() {
        String llvm = ir("""
                main:
                  set m: { a: "text", b: 7 }
                  print: m["a"]
                """);
        assertTrue(llvm.contains("call %ow.mixedmap* @__ow_native_mixedmap_new()"));
        assertTrue(llvm.contains("call %ow.mixed @__ow_native_mixedmap_get("));
    }

    @Test public void stringMapIndexUpdateRejectsIntegers() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { a: "text" }
                  set m["a"]: 7
                """));
        assertTrue(e.getMessage().contains("requires string values"));
    }

    @Test public void stringMapSetRejectsIntegerValue() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  print: map_set({ a: "text" }, "b", 10)
                """));
        assertTrue(e.getMessage().contains("expects a string value"));
    }

    @Test public void stringMapGetRejectsIntegerFallback() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  print: map_get({ a: "text" }, "b", 10)
                """));
        assertTrue(e.getMessage().contains("fallback must be a string"));
    }

    @Test public void mapIndexRejectsNonStringKey() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { a: "text" }
                  print: m[1]
                """));
        assertTrue(e.getMessage().contains("keys must be strings"));
    }

    @Test public void passingIntegerMapToStringMapFunctionRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                fn count(m: string_map):
                  returns: int
                  return: map_size(m)
                main:
                  print: count({ a: 1 })
                """));
        assertTrue(e.getMessage().contains("expects %ow.strmap*"));
    }
}
