package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** Heterogeneous string/int map LLVM lowering and owned tagged-value tests. */
public class NativeMixedDictionaryLlvmTest {
    private String ir(String source) {
        Ast.Program ast = new Parser(new Lexer(source, "mixed-map.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(ast, "mixed_map_test");
    }

    @Test public void mixedLiteralEmitsTaggedRuntimeAndMap() {
        String ll = ir("""
                main:
                  set m: { name: "OW", version: 7 }
                  print: m["name"]
                  print: m["version"]
                """);
        assertTrue(ll.contains("%ow.mixed = type { i32, i32, %ow.string, i8* }"));
        assertTrue(ll.contains("%ow.mixedmap.entry = type { %ow.string, %ow.mixed, %ow.mixedmap.entry* }"));
        assertTrue(ll.contains("call %ow.mixedmap* @__ow_native_mixedmap_new()"));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_string("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_i32("));
        assertTrue(ll.contains("call void @__ow_native_mixed_print("));
    }

    @Test public void homogeneousIntAndStringMapsKeepExistingAbi() {
        String ll = ir("""
                main:
                  set integers: { a: 3, b: 7 }
                  set strings: { a: "x", b: "y" }
                  print: integers["a"]
                  print: strings["a"]
                """);
        assertTrue(ll.contains("call %ow.intmap* @__ow_native_intmap_new()"));
        assertTrue(ll.contains("call %ow.strmap* @__ow_native_strmap_new()"));
        int main = ll.indexOf("define i32 @main()");
        String user = ll.substring(main);
        assertFalse(user.contains("call %ow.mixedmap* @__ow_native_mixedmap_new()"));
    }

    @Test public void mixedMapAcceptsRuntimeTypeChanges() {
        String ll = ir("""
                main:
                  set m: { a: "first", b: 2 }
                  set m["a"]: 10
                  set m["b"]: "second"
                  print: m["a"]
                  print: m["b"]
                """);
        assertTrue(ll.contains("call void @__ow_native_mixedmap_set("));
        assertTrue(ll.contains("call void @__ow_native_mixed_release(%ow.mixed %prior)"));
    }

    @Test public void nodeCleanupReleasesOwnedStringValues() {
        String ll = ir("""
                main:
                  set m: { a: "x" + "y", count: 4 }
                  set other: m
                  set m: { next: 1, text: "z" }
                  print: other["a"]
                """);
        assertTrue(ll.contains("call void @__ow_native_mixedmap_retain("));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_release("));
        assertTrue(ll.contains("call void @__ow_native_mixed_release(%ow.mixed %value)"));
        assertTrue(ll.contains("call void @__ow_native_string_release(%ow.string %text)"));
    }

    @Test public void dynamicStringVariablesAreRetainedWhenBoxed() {
        String ll = ir("""
                main:
                  set text: "Hello" + " OW"
                  set m: { a: text, n: 3 }
                  set text: "Changed"
                  print: m["a"]
                """);
        int main = ll.indexOf("define i32 @main()");
        assertTrue(ll.indexOf("call void @__ow_native_string_retain(%ow.string", main) > main);
        assertTrue(ll.indexOf("call %ow.mixed @__ow_native_mixed_wrap_string(", main) > main);
    }

    @Test public void mapGetFallbackIsOnlyCalledInAbsentBranch() {
        String ll = ir("""
                fn fallback():
                  returns: string
                  print: "FALLBACK"
                  return: "x" + "y"
                main:
                  set m: { a: "one", b: 2 }
                  print: map_get(m, "a", fallback())
                """);
        int main = ll.indexOf("define i32 @main()");
        int absent = ll.indexOf("mixedmap.get.absent.", main);
        int call = ll.indexOf("call %ow.string @fallback()", main);
        assertTrue(absent > main && call > absent);
        assertTrue(ll.contains("call void @__ow_native_mixed_retain("));
    }

    @Test public void mapOperationsWorkForMixedMap() {
        String ll = ir("""
                main:
                  set m: { label: "a", count: 1 }
                  print: map_has(m, "label")
                  set m: map_set(m, "count", "two")
                  set m: map_delete(m, "label")
                  print: map_size(m)
                """);
        assertTrue(ll.contains("call i1 @__ow_native_mixedmap_has("));
        assertTrue(ll.contains("call i32 @__ow_native_mixedmap_size("));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_delete("));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_set("));
    }

    @Test public void mixedFunctionParameterAndReturnType() {
        String ll = ir("""
                fn make():
                  returns: mixed_map
                  return: { count: 3, label: "OW" }
                fn peek(m: mixed_map):
                  returns: mixed
                  return: m["label"]
                main:
                  print: peek(make())
                """);
        assertTrue(ll.contains("define %ow.mixedmap* @make()"));
        assertTrue(ll.contains("define %ow.mixed @peek(%ow.mixedmap* %m.arg)"));
        assertTrue(ll.contains("call %ow.mixed @peek("));
    }

    @Test public void rejectsMapWithUnsupportedNestedValue() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { count: 3, nested: [1, 2] }
                """));
        assertTrue(e.getMessage().contains("values must be integers or strings"));
    }

    @Test public void integerMapRejectsStringTypeChange() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { one: 1 }
                  set m["one"]: "string"
                """));
        assertTrue(e.getMessage().contains("requires integer values"));
    }

    @Test public void stringMapRejectsIntegerTypeChange() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { one: "string" }
                  set m["one"]: 1
                """));
        assertTrue(e.getMessage().contains("requires string values"));
    }

    @Test public void mixedMapRequiresStringKeys() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { one: "str", two: 2 }
                  print: m[1]
                """));
        assertTrue(e.getMessage().contains("keys must be strings"));
    }

    @Test public void doesNotExecuteFallbackForPresentMixedMapKey() {
        String ll = ir("""
                fn fallback():
                  returns: int
                  return: 99
                main:
                  set m: { a: "hi", b: 2 }
                  print: map_get(m, "b", fallback())
                """);
        assertTrue(ll.contains("mixedmap.get.present."));
        assertTrue(ll.contains("mixedmap.get.absent."));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_i32("));
    }
}
