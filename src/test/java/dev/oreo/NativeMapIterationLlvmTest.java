package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** Native insertion-order enumeration and ARC snapshots for all map ABIs. */
public class NativeMapIterationLlvmTest {
    private String ir(String source) {
        Ast.Program p = new Parser(new Lexer(source, "map-iteration.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(p, "map_iteration_test");
    }

    @Test public void allThreeMapKindsKeepExplicitOrderedLinks() {
        String ll = ir("""
                main:
                  set a: { x: 1 }
                  set b: { y: "two" }
                  set c: { name: "OW", version: 6 }
                """);
        for (String kind : new String[]{"intmap", "strmap", "mixedmap"}) {
            String valueType = kind.equals("intmap") ? "i32" :
                    kind.equals("strmap") ? "%ow.string" : "%ow.mixed";
            assertTrue(ll.contains("%ow." + kind + ".entry = type { %ow.string, " + valueType
                    + ", %ow." + kind + ".entry*, %ow." + kind + ".entry*, %ow." + kind + ".entry* }"));
            assertTrue(ll.contains("%ow." + kind + " = type { %ow." + kind
                    + ".entry**, i32, i32, i32, %ow." + kind + ".entry*, %ow." + kind + ".entry* }"));
            assertTrue(ll.contains("define void @__ow_native_" + kind + "_fill_keys("));
            assertTrue(ll.contains("define void @__ow_native_" + kind + "_fill_values("));
        }
    }

    @Test public void insertionOrderSurvivesHashRehashAndDeletion() {
        String ll = ir("""
                main:
                  set m: { first: 1, second: 2 }
                  set m: map_set(m, "third", 3)
                  set m: map_delete(m, "second")
                  set m: map_set(m, "second", 22)
                  set names: map_keys(m)
                  print: names[0]
                """);
        assertTrue(ll.contains("finishOrder:"));
        assertTrue(ll.contains("removeOrderHead:"));
        assertTrue(ll.contains("removeOrderTail:"));
        assertTrue(ll.contains("removeOrderInterior:"));
        assertTrue(ll.contains("%orderNextPtr = getelementptr %ow.intmap.entry"));
        assertTrue(ll.contains("call void @__ow_native_intmap_fill_keys(%ow.intmap*"));
    }

    @Test public void mapKeysReturnsManagedStringArray() {
        String ll = ir("""
                main:
                  set m: { a: 1, b: 2 }
                  set keys: map_keys(m)
                  print: len(keys)
                  print: keys[0]
                """);
        assertTrue(ll.contains("call %ow.strarray @__ow_native_strarray_new(i32"));
        assertTrue(ll.contains("call void @__ow_native_intmap_fill_keys("));
        assertTrue(ll.contains("call void @__ow_native_string_retain(%ow.string %key)"));
    }

    @Test public void integerMapValuesProduceNativeIntegerArray() {
        String ll = ir("""
                main:
                  set m: { a: 12, b: 34 }
                  set vals: map_values(m)
                  print: vals[1]
                """);
        assertTrue(ll.contains("call void @__ow_native_intmap_fill_values(%ow.intmap*"));
        assertTrue(ll.contains("getelementptr i32, i32* %output"));
    }

    @Test public void stringMapValuesRetainEachStringIntoSnapshot() {
        String ll = ir("""
                main:
                  set m: { a: "hello" + " world" }
                  set vals: map_values(m)
                  set m: { b: "gone" }
                  print: vals[0]
                """);
        assertTrue(ll.contains("call void @__ow_native_strmap_fill_values(%ow.strmap*"));
        assertTrue(ll.contains("call void @__ow_native_string_retain(%ow.string %value)"));
        assertTrue(ll.contains("call void @__ow_native_strarray_release("));
    }

    @Test public void mixedMapValuesProduceTaggedMixedArray() {
        String ll = ir("""
                main:
                  set m: { name: "OW", version: 6 }
                  set vals: map_values(m)
                  print: vals[0]
                  print: vals[1]
                """);
        assertTrue(ll.contains("%ow.mixedarray = type { i32, %ow.mixed*, i32* }"));
        assertTrue(ll.contains("call %ow.mixedarray @__ow_native_mixedarray_new(i32"));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_fill_values("));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_mixedarray_get("));
    }

    @Test public void mixedSnapshotsRetainNestedMapReferences() {
        String ll = ir("""
                main:
                  set m: { nested: { port: 5432 }, name: "OW" }
                  set vals: map_values(m)
                  set m: { key: 1, text: "x" }
                  print: vals[0]["port"]
                """);
        assertTrue(ll.contains("call void @__ow_native_mixed_retain(%ow.mixed %value)"));
        assertTrue(ll.contains("call void @__ow_native_mixed_release(%ow.mixed %value)"));
        assertTrue(ll.contains("call %ow.mixed @__ow_native_nested_index("));
    }

    @Test public void temporaryMapSnapshotIsOwnedAfterMapRelease() {
        String ll = ir("""
                main:
                  print: map_keys({ first: 1, second: 2 })[1]
                  print: map_values({ name: "OW", year: 2026 })[0]
                """);
        assertTrue(ll.contains("call void @__ow_native_intmap_release("));
        assertTrue(ll.contains("call void @__ow_native_mixedmap_release("));
        assertTrue(ll.contains("call void @__ow_native_mixedarray_release("));
    }

    @Test public void emptyMapsProduceZeroLengthSnapshots() {
        String ll = ir("""
                main:
                  set m: {}
                  print: len(map_keys(m))
                  print: len(map_values(m))
                """);
        assertTrue(ll.contains("call void @__ow_native_intmap_fill_keys("));
        assertTrue(ll.contains("call void @__ow_native_intmap_fill_values("));
    }

    @Test public void arityValidationAppliesToEnumeration() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set m: { first: 1 }
                  print: map_keys(m, m)
                """));
        assertTrue(e.getMessage().contains("map_keys expects 1 argument"));
    }

    @Test public void mapValuesRequiresDictionary() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> ir("""
                main:
                  set values: map_values([1, 2, 3])
                """));
        assertTrue(e.getMessage().contains("expects a native dictionary"));
    }

    @Test public void typedMixedArrayCanBeReturnedFromFunction() {
        String ll = ir("""
                fn entries(m: mixed_map):
                  returns: mixed_array
                  return: map_values(m)
                main:
                  set m: { name: "OW", version: 6 }
                  print: entries(m)[0]
                """);
        assertTrue(ll.contains("define %ow.mixedarray @entries(%ow.mixedmap* %m.arg)"));
        assertTrue(ll.contains("call void @__ow_native_mixedarray_retain("));
        assertTrue(ll.contains("call void @__ow_native_mixedarray_release("));
    }
}
