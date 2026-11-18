package dev.oreo;

import org.junit.Test;

import static org.junit.Assert.*;

/** The three native map ABIs share collision handling and amortized O(1) lookups. */
public class NativeHashMapLlvmTest {
    private String ir(String source) {
        Ast.Program ast = new Parser(new Lexer(source, "hashmaps.ow").lexAll()).parseProgram();
        return new LlvmIrGen().generate(ast, "hashmap_test");
    }

    @Test public void usesPowerOfTwoBucketArrayAndFnvHash() {
        String ll = ir("""
                main:
                  set m: { a: 1 }
                  print: m["a"]
                """);
        assertTrue(ll.contains("define i64 @__ow_native_hash_utf8(%ow.string %key)"));
        assertTrue(ll.contains("%hash = phi i64 [ -3750763034362895579, %entry ]"));
        assertTrue(ll.contains("%multiplied = mul i64 %xored, 1099511628211"));
        assertTrue(ll.contains("and i64 %hash, %mask"));
        assertTrue(ll.contains("call i8* @calloc(i64 16, i64 %pointerBytes)"));
        assertTrue(ll.contains("%ow.intmap = type { %ow.intmap.entry**, i32, i32, i32 }"));
    }

    @Test public void bucketLookupUsesCollisionChainNotGlobalLinkedList() {
        String ll = ir("""
                main:
                  set m: { a: 1, b: 2 }
                  print: m["b"]
                """);
        int begin = ll.indexOf("define %ow.intmap.entry* @__ow_native_intmap_find(");
        int end = ll.indexOf("\n}", begin);
        String find = ll.substring(begin, end);
        assertTrue(find.contains("call %ow.intmap.entry** @__ow_native_intmap_bucket_slot("));
        assertTrue(find.contains("load %ow.intmap.entry*, %ow.intmap.entry** %slot"));
        assertTrue(find.contains("call i32 @strcmp("));
        assertTrue(find.contains("getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 2"));
    }

    @Test public void rehashMovesNodesWithoutCopyingOrDroppingStringReferences() {
        String ll = ir("""
                main:
                  set m: { "hello": "world" }
                  print: m["hello"]
                """);
        int start = ll.indexOf("define void @__ow_native_strmap_rehash(");
        int end = ll.indexOf("\n}", start);
        String rehash = ll.substring(start, end);
        assertTrue(rehash.contains("store i32 %capacity, i32* %capPtr"));
        assertTrue(rehash.contains("call %ow.strmap.entry** @__ow_native_strmap_bucket_slot("));
        assertTrue(rehash.contains("call void @free(i8* %oldRaw)"));
        assertFalse(rehash.contains("@__ow_native_string_release"));
        assertFalse(rehash.contains("@__ow_native_string_retain"));
        assertTrue(ll.contains("%grow = icmp ugt i64 %loadTimesFour, %capTimesThree"));
    }

    @Test public void allThreeMapKindsHaveHashBucketsAndGrowth() {
        String ll = ir("""
                main:
                  set ints: { a: 1 }
                  set strings: { a: "x" }
                  set mixed: { a: "x", b: 1 }
                  print: map_size(ints)
                  print: map_size(strings)
                  print: map_size(mixed)
                """);
        for (String kind : new String[]{"intmap", "strmap", "mixedmap"}) {
            assertTrue(ll.contains("define void @__ow_native_" + kind + "_rehash("));
            assertTrue(ll.contains("define %ow." + kind + ".entry** @__ow_native_" + kind + "_bucket_slot("));
            assertTrue(ll.contains("call void @__ow_native_" + kind + "_rehash(%ow." + kind + "* %map)"));
        }
    }

    @Test public void deletionUsesBucketLinkAndStillReleasesKeysAndValues() {
        String ll = ir("""
                main:
                  set a: { key: "owned" + " value" }
                  set a: map_delete(a, "key")
                """);
        int start = ll.indexOf("define void @__ow_native_strmap_delete(");
        int end = ll.indexOf("\n}", start);
        String delete = ll.substring(start, end);
        assertTrue(delete.contains("call %ow.strmap.entry** @__ow_native_strmap_bucket_slot("));
        assertTrue(delete.contains("store %ow.strmap.entry* %next, %ow.strmap.entry** %pointer"));
        assertTrue(delete.contains("call void @__ow_native_string_release(%ow.string %stored)"));
        assertTrue(delete.contains("call void @__ow_native_string_release(%ow.string %val)"));
        assertTrue(delete.contains("call void @free(i8* %rawNode)"));
    }

    @Test public void lastOwnerDestroysAllBucketsAndTheirNodes() {
        String ll = ir("""
                main:
                  set m: { a: "s", b: 1 }
                """);
        int start = ll.indexOf("define void @__ow_native_mixedmap_release(");
        int end = ll.indexOf("\n}", start);
        String cleanup = ll.substring(start, end);
        assertTrue(cleanup.contains("visitBucket:"));
        assertTrue(cleanup.contains("call void @__ow_native_mixed_release(%ow.mixed %value)"));
        assertTrue(cleanup.contains("call void @__ow_native_string_release(%ow.string %key)"));
        assertTrue(cleanup.contains("call void @free(i8* %rawBuckets)"));
        assertTrue(cleanup.contains("call void @free(i8* %rawMap)"));
    }

    @Test public void resizeUsesCheckedCapacityAndAllocation() {
        String ll = ir("""
                main:
                  set m: { a: 1 }
                """);
        int start = ll.indexOf("define void @__ow_native_intmap_rehash(");
        int end = ll.indexOf("\n}", start);
        String rehash = ll.substring(start, end);
        assertTrue(rehash.contains("%canGrow = icmp ult i32 %oldCapacity, 1073741824"));
        assertTrue(rehash.contains("%raw = call i8* @calloc(i64 %newSlots, i64 %pointerBytes)"));
        assertTrue(rehash.contains("call void @abort()"));
    }

    @Test public void originalMapOperationsRemainAvailable() {
        String ll = ir("""
                main:
                  set a: { a: 1 }
                  set b: { b: "x" }
                  set c: { c: 1, d: "y" }
                  print: map_get(a, "a", 2)
                  print: map_get(b, "b", "?")
                  print: map_get(c, "d", "?")
                """);
        for (String kind : new String[]{"intmap", "strmap", "mixedmap"}) {
            assertTrue(ll.contains("define void @__ow_native_" + kind + "_set("));
            assertTrue(ll.contains("define void @__ow_native_" + kind + "_delete("));
            assertTrue(ll.contains("define i32 @__ow_native_" + kind + "_size("));
            assertTrue(ll.contains("define i1 @__ow_native_" + kind + "_has("));
        }
    }
}
