package dev.oreo;

/**
 * Runtime utilities for nested native dictionaries stored inside tagged values.
 *
 * Tags: 0=i32, 1=string, 2=int_map, 3=string_map, 4=mixed_map.
 * The pointer payload is deliberately opaque in the tagged ABI. The map
 * helpers preserve and release each child's existing ARC reference.
 *
 * Cycles of mixed maps are rejected on insertion, including transitive cycles.
 * The reachability walk is recursive because all previously accepted graphs
 * are DAGs (no cycles can have been added by the guarded insertion routine).
 */
final class NativeNestedMapIr {
    private NativeNestedMapIr() {}

    static String generate() {
        return """
                define %ow.mixed @__ow_native_mixed_wrap_intmap(%ow.intmap* %map) {
                entry:
                  %raw = bitcast %ow.intmap* %map to i8*
                  %tag = insertvalue %ow.mixed zeroinitializer, i32 2, 0
                  %result = insertvalue %ow.mixed %tag, i8* %raw, 3
                  ret %ow.mixed %result
                }

                define %ow.mixed @__ow_native_mixed_wrap_strmap(%ow.strmap* %map) {
                entry:
                  %raw = bitcast %ow.strmap* %map to i8*
                  %tag = insertvalue %ow.mixed zeroinitializer, i32 3, 0
                  %result = insertvalue %ow.mixed %tag, i8* %raw, 3
                  ret %ow.mixed %result
                }

                define %ow.mixed @__ow_native_mixed_wrap_mixedmap(%ow.mixedmap* %map) {
                entry:
                  %raw = bitcast %ow.mixedmap* %map to i8*
                  %tag = insertvalue %ow.mixed zeroinitializer, i32 4, 0
                  %result = insertvalue %ow.mixed %tag, i8* %raw, 3
                  ret %ow.mixed %result
                }

                define void @__ow_native_nested_retain(%ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %raw = extractvalue %ow.mixed %value, 3
                  switch i32 %tag, label %invalid [
                    i32 2, label %intMap
                    i32 3, label %stringMap
                    i32 4, label %mixedMap
                  ]
                intMap:
                  %int = bitcast i8* %raw to %ow.intmap*
                  call void @__ow_native_intmap_retain(%ow.intmap* %int)
                  ret void
                stringMap:
                  %str = bitcast i8* %raw to %ow.strmap*
                  call void @__ow_native_strmap_retain(%ow.strmap* %str)
                  ret void
                mixedMap:
                  %mixed = bitcast i8* %raw to %ow.mixedmap*
                  call void @__ow_native_mixedmap_retain(%ow.mixedmap* %mixed)
                  ret void
                invalid:
                  call void @abort()
                  unreachable
                }

                define void @__ow_native_nested_release(%ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %raw = extractvalue %ow.mixed %value, 3
                  switch i32 %tag, label %invalid [
                    i32 2, label %intMap
                    i32 3, label %stringMap
                    i32 4, label %mixedMap
                  ]
                intMap:
                  %int = bitcast i8* %raw to %ow.intmap*
                  call void @__ow_native_intmap_release(%ow.intmap* %int)
                  ret void
                stringMap:
                  %str = bitcast i8* %raw to %ow.strmap*
                  call void @__ow_native_strmap_release(%ow.strmap* %str)
                  ret void
                mixedMap:
                  %mixed = bitcast i8* %raw to %ow.mixedmap*
                  call void @__ow_native_mixedmap_release(%ow.mixedmap* %mixed)
                  ret void
                invalid:
                  call void @abort()
                  unreachable
                }

                ; An indexed access returns a new owned tagged value.
                ; It is safe to release a temporary parent immediately after.
                define %ow.mixed @__ow_native_nested_index(%ow.mixed %container, %ow.string %key) {
                entry:
                  %tag = extractvalue %ow.mixed %container, 0
                  %raw = extractvalue %ow.mixed %container, 3
                  switch i32 %tag, label %notMap [
                    i32 2, label %intMap
                    i32 3, label %strMap
                    i32 4, label %mixedMap
                  ]
                intMap:
                  %ints = bitcast i8* %raw to %ow.intmap*
                  %number = call i32 @__ow_native_intmap_get(%ow.intmap* %ints, %ow.string %key)
                  %intResult = call %ow.mixed @__ow_native_mixed_wrap_i32(i32 %number)
                  ret %ow.mixed %intResult
                strMap:
                  %strings = bitcast i8* %raw to %ow.strmap*
                  %string = call %ow.string @__ow_native_strmap_get(%ow.strmap* %strings, %ow.string %key)
                  %stringResult = call %ow.mixed @__ow_native_mixed_wrap_string(%ow.string %string)
                  ret %ow.mixed %stringResult
                mixedMap:
                  %nested = bitcast i8* %raw to %ow.mixedmap*
                  %result = call %ow.mixed @__ow_native_mixedmap_get(%ow.mixedmap* %nested, %ow.string %key)
                  ret %ow.mixed %result
                notMap:
                  call void @abort()
                  unreachable
                }

                ; Recursive DAG reachability checks prevent maps from
                ; owning themselves, including through shared child maps.
                define i1 @__ow_native_nested_reaches(%ow.mixedmap* %start, %ow.mixedmap* %target) {
                entry:
                  %same = icmp eq %ow.mixedmap* %start, %target
                  br i1 %same, label %yes, label %scan
                scan:
                  %bucketsPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %start, i32 0, i32 0
                  %buckets = load %ow.mixedmap.entry**, %ow.mixedmap.entry*** %bucketsPtr
                  %capPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %start, i32 0, i32 3
                  %capacity = load i32, i32* %capPtr
                  br label %bucketLoop
                bucketLoop:
                  %i = phi i32 [ 0, %scan ], [ %nextI, %nextBucket ]
                  %more = icmp ult i32 %i, %capacity
                  br i1 %more, label %loadBucket, label %no
                loadBucket:
                  %slot = getelementptr %ow.mixedmap.entry*, %ow.mixedmap.entry** %buckets, i32 %i
                  %head = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %slot
                  br label %nodeLoop
                nodeLoop:
                  %node = phi %ow.mixedmap.entry* [ %head, %loadBucket ], [ %next, %advance ]
                  %end = icmp eq %ow.mixedmap.entry* %node, null
                  br i1 %end, label %nextBucket, label %inspect
                inspect:
                  %valuePtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 1
                  %value = load %ow.mixed, %ow.mixed* %valuePtr
                  %tag = extractvalue %ow.mixed %value, 0
                  %isMixedMap = icmp eq i32 %tag, 4
                  br i1 %isMixedMap, label %walkChild, label %advance
                walkChild:
                  %raw = extractvalue %ow.mixed %value, 3
                  %child = bitcast i8* %raw to %ow.mixedmap*
                  %found = call i1 @__ow_native_nested_reaches(%ow.mixedmap* %child, %ow.mixedmap* %target)
                  br i1 %found, label %yes, label %advance
                advance:
                  %nextPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 2
                  %next = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %nextPtr
                  br label %nodeLoop
                nextBucket:
                  %nextI = add i32 %i, 1
                  br label %bucketLoop
                yes:
                  ret i1 true
                no:
                  ret i1 false
                }

                define void @__ow_native_nested_guard_cycle(%ow.mixedmap* %parent, %ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %isMixedMap = icmp eq i32 %tag, 4
                  br i1 %isMixedMap, label %check, label %done
                check:
                  %raw = extractvalue %ow.mixed %value, 3
                  %candidate = bitcast i8* %raw to %ow.mixedmap*
                  %cycle = call i1 @__ow_native_nested_reaches(%ow.mixedmap* %candidate, %ow.mixedmap* %parent)
                  br i1 %cycle, label %invalid, label %done
                invalid:
                  call void @abort()
                  unreachable
                done:
                  ret void
                }

                """;
    }
}
