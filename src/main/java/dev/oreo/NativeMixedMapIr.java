package dev.oreo;

/** Experimental tagged mixed-type native dictionary runtime.
 * LLVM map nodes own keys (strings) and tagged values (i32 or managed strings).
 * Lifetime and lookup behavior are kept aligned with NativeStringMapIr.
 */
final class NativeMixedMapIr {
    private NativeMixedMapIr() {}

    static String generate() {
        return mixedHelpers() + """

                define %ow.mixedmap* @__ow_native_mixedmap_new() {
                entry:
                  %end = getelementptr %ow.mixedmap, %ow.mixedmap* null, i32 1
                  %bytes = ptrtoint %ow.mixedmap* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %created, label %oom
                oom:
                  call void @abort()
                  unreachable
                created:
                  %map = bitcast i8* %raw to %ow.mixedmap*
                  %refs = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 1
                  store i32 1, i32* %refs
                  ret %ow.mixedmap* %map
                }

                define void @__ow_native_mixedmap_retain(%ow.mixedmap* %map) {
                entry:
                  %absent = icmp eq %ow.mixedmap* %map, null
                  br i1 %absent, label %done, label %retain
                retain:
                  %refs = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = add i32 %before, 1
                  store i32 %after, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_mixedmap_release(%ow.mixedmap* %map) {
                entry:
                  %absent = icmp eq %ow.mixedmap* %map, null
                  br i1 %absent, label %done, label %decrement
                decrement:
                  %refs = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %headPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 0
                  %head = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %headPtr
                  br label %loop
                loop:
                  %nodeToFree = phi %ow.mixedmap.entry* [ %head, %startCleanup ], [ %next, %freeNode ]
                  %empty = icmp eq %ow.mixedmap.entry* %nodeToFree, null
                  br i1 %empty, label %disposeMap, label %freeNode
                freeNode:
                  %keyPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %nodeToFree, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  call void @__ow_native_string_release(%ow.string %key)
                  %valuePtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %nodeToFree, i32 0, i32 1
                  %value = load %ow.mixed, %ow.mixed* %valuePtr
                  call void @__ow_native_mixed_release(%ow.mixed %value)
                  %nextPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %nodeToFree, i32 0, i32 2
                  %next = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %nextPtr
                  %rawNode = bitcast %ow.mixedmap.entry* %nodeToFree to i8*
                  call void @free(i8* %rawNode)
                  br label %loop
                disposeMap:
                  %rawMap = bitcast %ow.mixedmap* %map to i8*
                  call void @free(i8* %rawMap)
                  br label %done
                done:
                  ret void
                }

                define %ow.mixedmap.entry* @__ow_native_mixedmap_find(%ow.mixedmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 0
                  %head = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %headPtr
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %node = phi %ow.mixedmap.entry* [ %head, %entry ], [ %next, %advance ]
                  %empty = icmp eq %ow.mixedmap.entry* %node, null
                  br i1 %empty, label %missing, label %compare
                compare:
                  %storedPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %storedPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %equal = icmp eq i32 %diff, 0
                  br i1 %equal, label %found, label %advance
                advance:
                  %nextPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 2
                  %next = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %nextPtr
                  br label %loop
                found:
                  ret %ow.mixedmap.entry* %node
                missing:
                  ret %ow.mixedmap.entry* null
                }

                define void @__ow_native_mixedmap_set(%ow.mixedmap* %map, %ow.string %key, %ow.mixed %value) {
                entry:
                  %old = call %ow.mixedmap.entry* @__ow_native_mixedmap_find(%ow.mixedmap* %map, %ow.string %key)
                  %absent = icmp eq %ow.mixedmap.entry* %old, null
                  br i1 %absent, label %insert, label %update
                update:
                  %valuePtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %old, i32 0, i32 1
                  %prior = load %ow.mixed, %ow.mixed* %valuePtr
                  store %ow.mixed %value, %ow.mixed* %valuePtr
                  call void @__ow_native_mixed_release(%ow.mixed %prior)
                  call void @__ow_native_string_release(%ow.string %key)
                  br label %done
                insert:
                  %end = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* null, i32 1
                  %bytes = ptrtoint %ow.mixedmap.entry* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %store, label %oom
                oom:
                  call void @__ow_native_string_release(%ow.string %key)
                  call void @abort()
                  unreachable
                store:
                  %node = bitcast i8* %raw to %ow.mixedmap.entry*
                  %keyPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 0
                  store %ow.string %key, %ow.string* %keyPtr
                  %slot = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 1
                  store %ow.mixed %value, %ow.mixed* %slot
                  %headPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 0
                  %previous = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %headPtr
                  %nextPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 2
                  store %ow.mixedmap.entry* %previous, %ow.mixedmap.entry** %nextPtr
                  store %ow.mixedmap.entry* %node, %ow.mixedmap.entry** %headPtr
                  %countPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = add i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                define %ow.mixed @__ow_native_mixedmap_get(%ow.mixedmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.mixedmap.entry* @__ow_native_mixedmap_find(%ow.mixedmap* %map, %ow.string %key)
                  %missing = icmp eq %ow.mixedmap.entry* %found, null
                  br i1 %missing, label %noKey, label %hasKey
                noKey:
                  call void @abort()
                  unreachable
                hasKey:
                  %ptr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %found, i32 0, i32 1
                  %value = load %ow.mixed, %ow.mixed* %ptr
                  call void @__ow_native_mixed_retain(%ow.mixed %value)
                  ret %ow.mixed %value
                }

                define i1 @__ow_native_mixedmap_has(%ow.mixedmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.mixedmap.entry* @__ow_native_mixedmap_find(%ow.mixedmap* %map, %ow.string %key)
                  %present = icmp ne %ow.mixedmap.entry* %found, null
                  ret i1 %present
                }

                define i32 @__ow_native_mixedmap_size(%ow.mixedmap* %map) {
                entry:
                  %countPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  ret i32 %count
                }

                define void @__ow_native_mixedmap_delete(%ow.mixedmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 0
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %link = phi %ow.mixedmap.entry** [ %headPtr, %entry ], [ %nextLink, %advance ]
                  %node = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %link
                  %empty = icmp eq %ow.mixedmap.entry* %node, null
                  br i1 %empty, label %done, label %compare
                compare:
                  %keyPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %keyPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %same = icmp eq i32 %diff, 0
                  br i1 %same, label %remove, label %advance
                advance:
                  %nextLink = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 2
                  br label %loop
                remove:
                  %nextPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 2
                  %next = load %ow.mixedmap.entry*, %ow.mixedmap.entry** %nextPtr
                  store %ow.mixedmap.entry* %next, %ow.mixedmap.entry** %link
                  call void @__ow_native_string_release(%ow.string %stored)
                  %valPtr = getelementptr %ow.mixedmap.entry, %ow.mixedmap.entry* %node, i32 0, i32 1
                  %val = load %ow.mixed, %ow.mixed* %valPtr
                  call void @__ow_native_mixed_release(%ow.mixed %val)
                  %rawNode = bitcast %ow.mixedmap.entry* %node to i8*
                  call void @free(i8* %rawNode)
                  %countPtr = getelementptr %ow.mixedmap, %ow.mixedmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = sub i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                """;
    }

    /** Tag 0 = i32, tag 1 = managed UTF-8 string. */
    private static String mixedHelpers() {
        return """
                define %ow.mixed @__ow_native_mixed_wrap_i32(i32 %value) {
                entry:
                  %result = insertvalue %ow.mixed zeroinitializer, i32 %value, 1
                  ret %ow.mixed %result
                }

                define %ow.mixed @__ow_native_mixed_wrap_string(%ow.string %value) {
                entry:
                  %tag = insertvalue %ow.mixed zeroinitializer, i32 1, 0
                  %result = insertvalue %ow.mixed %tag, %ow.string %value, 2
                  ret %ow.mixed %result
                }

                define void @__ow_native_mixed_retain(%ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %isString = icmp eq i32 %tag, 1
                  br i1 %isString, label %retainString, label %done
                retainString:
                  %text = extractvalue %ow.mixed %value, 2
                  call void @__ow_native_string_retain(%ow.string %text)
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_mixed_release(%ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %isString = icmp eq i32 %tag, 1
                  br i1 %isString, label %releaseString, label %done
                releaseString:
                  %text = extractvalue %ow.mixed %value, 2
                  call void @__ow_native_string_release(%ow.string %text)
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_mixed_print(%ow.mixed %value) {
                entry:
                  %tag = extractvalue %ow.mixed %value, 0
                  %isString = icmp eq i32 %tag, 1
                  br i1 %isString, label %string, label %integer
                string:
                  %text = extractvalue %ow.mixed %value, 2
                  %bytes = extractvalue %ow.string %text, 0
                  %fmtStr = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0
                  %printedStr = call i32 (i8*, ...) @printf(i8* %fmtStr, i8* %bytes)
                  br label %done
                integer:
                  %number = extractvalue %ow.mixed %value, 1
                  %fmtInt = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0
                  %printedInt = call i32 (i8*, ...) @printf(i8* %fmtInt, i32 %number)
                  br label %done
                done:
                  ret void
                }

                """;
    }

}
