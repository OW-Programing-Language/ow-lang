package dev.oreo;

import org.junit.Test;
import static org.junit.Assert.*;

/** Nested native map values, recursive ARC and cycle prevention. */
public class NativeNestedMapLlvmTest {
    private String ir(String source) {
        Ast.Program p = new Parser(new Lexer(source, "nested-map.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(p, "nested_map_test");
    }

    @Test public void nestedHomogeneousMapChoosesTaggedParent() {
        String ll = ir("""
                main:
                  set config: { database: { host: "localhost", port: 5432 } }
                  print: config["database"]["port"]
                """);
        assertTrue(ll.contains("%ow.mixed = type { i32, i32, %ow.string, i8* }"));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_mixedmap("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_nested_index("));
    }

    @Test public void nestedIntegerAndStringMapsRetainTheirDistinctAbi() {
        String ll = ir("""
                main:
                  set config: { ports: { mysql: 3306 }, names: { web: "OW" } }
                  print: config["ports"]["mysql"]
                  print: config["names"]["web"]
                """);
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_intmap("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_strmap("));
        assertTrue(ll.contains("call i32 @__ow_native_intmap_get(%ow.intmap* %ints"));
        assertTrue(ll.contains("call %ow.string @__ow_native_strmap_get(%ow.strmap* %strings"));
    }

    @Test public void recursiveMapRetainDispatchesForAllThreeNativeTypes() {
        String ll = ir("""
                main:
                  set values: { a: 1, b: { child: "x" } }
                """);
        assertTrue(ll.contains("define void @__ow_native_nested_retain("));
        assertTrue(ll.contains("call void @__ow_native_intmap_retain(%ow.intmap* %int)"));
        assertTrue(ll.contains("call void @__ow_native_strmap_retain(%ow.strmap* %str)"));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_retain(%ow.mixedmap* %mixed)"));
    }

    @Test public void recursiveMapReleaseDispatchesForAllThreeNativeTypes() {
        String ll = ir("""
                main:
                  set values: { a: 1, b: { child: "x" } }
                """);
        assertTrue(ll.contains("define void @__ow_native_nested_release("));
        assertTrue(ll.contains("call void @__ow_native_intmap_release(%ow.intmap* %int)"));
        assertTrue(ll.contains("call void @__ow_native_strmap_release(%ow.strmap* %str)"));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_release(%ow.mixedmap* %mixed)"));
        assertTrue(ll.contains("call void @__ow_native_nested_release(%ow.mixed %value)"));
    }

    @Test public void mixedTagRetainAndReleaseForwardNestedValues() {
        String ll = ir("""
                main:
                  set m: { nested: { name: "OW" } }
                  set alias: m
                  set m: { replacement: 4, child: { x: 1 } }
                """);
        assertTrue(ll.contains("call void @__ow_native_nested_retain(%ow.mixed %value)"));
        assertTrue(ll.contains("call void @__ow_native_nested_release(%ow.mixed %value)"));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_retain(%ow.mixedmap*"));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_release(%ow.mixedmap*"));
    }

    @Test public void nestedIndexResultIsOwnedAcrossTemporaryParentCleanup() {
        String ll = ir("""
                main:
                  print: { nested: { message: "Hel" + "lo" } }["nested"]["message"]
                """);
        int main = ll.indexOf("define i32 @main()");
        String user = ll.substring(main);
        int nested = user.indexOf("call %ow.mixed @__ow_native_nested_index(");
        int release = user.indexOf("call void @__ow_native_mixed_release(", nested);
        assertTrue(nested > 0 && release > nested);
        assertTrue(ll.contains("call %ow.string @__ow_native_strmap_get("));
    }

    @Test public void mapSetAndIndexMutationCanTransferNestedDictionaries() {
        String ll = ir("""
                main:
                  set m: { a: 1, b: "two" }
                  set child: { value: "nested" }
                  set m["child"]: child
                  set m: map_set(m, "other", { key: 99 })
                  print: m["child"]["value"]
                """);
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_strmap("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_intmap("));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_set("));
    }

    @Test public void twoLevelCycleGuardRejectsDirectAndTransitiveCycles() {
        String ll = ir("""
                main:
                  set first: { n: 1, s: "a" }
                  set second: { n: 2, s: "b" }
                  set first["child"]: second
                """);
        assertTrue(ll.contains("define void @__ow_native_nested_guard_cycle("));
        assertTrue(ll.contains("define i1 @__ow_native_nested_reaches("));
        assertTrue(ll.contains("call void @__ow_native_nested_guard_cycle(%ow.mixedmap* %map, %ow.mixed %value)"));
        assertTrue(ll.contains("call i1 @__ow_native_nested_reaches(%ow.mixedmap* %child, %ow.mixedmap* %target)"));
    }

    @Test public void nativeMixedTagStringAndIntegerBehaviorUnchanged() {
        String ll = ir("""
                main:
                  set m: { version: 6, name: "OW" }
                  print: m["name"]
                  print: m["version"]
                """);
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_i32("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixed_wrap_string("));
        assertTrue(ll.contains("call void @__ow_native_mixed_print("));
    }

    @Test public void typedMixedMapFunctionReturnsNestedValueSafely() {
        String ll = ir("""
                fn build():
                  returns: mixed_map
                  return: { database: { port: 5432 }, version: 1 }
                fn getPort(root: mixed_map):
                  returns: mixed
                  return: root["database"]["port"]
                main:
                  print: getPort(build())
                """);
        assertTrue(ll.contains("define %ow.mixedmap* @build()"));
        assertTrue(ll.contains("define %ow.mixed @getPort(%ow.mixedmap* %root.arg)"));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_nested_index("));
    }

    @Test public void nestedArrayValueStillExplicitlyUnsupported() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { numbers: [1, 2, 3] }
                """));
        assertTrue(e.getMessage().contains("native dictionary values must be integers, strings or maps"));
    }
}
