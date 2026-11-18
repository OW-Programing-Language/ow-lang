package dev.oreo;

/**
 * Experimental native runtime for homogeneous string-valued OW dictionaries.
 * Each map node owns a key and value reference. Map-returned string values
 * are retained before returning and are owned by the LLVM caller.
 *
 * The runtime intentionally retains the linked-list O(n) lookup strategy of
 * the first integer-valued native dictionary milestone.
 */
final class NativeStringMapIr {
    private NativeStringMapIr() {}

    static String generate() {
        return """
                define %ow.strmap* @__ow_native_strmap_new() {
                entry:
                  %end = getelementptr %ow.strmap, %ow.strmap* null, i32 1
                  %bytes = ptrtoint %ow.strmap* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %created, label %oom
                oom:
                  call void @abort()
                  unreachable
                created:
                  %map = bitcast i8* %raw to %ow.strmap*
                  %refs = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 1
                  store i32 1, i32* %refs
                  ret %ow.strmap* %map
                }

                define void @__ow_native_strmap_retain(%ow.strmap* %map) {
                entry:
                  %absent = icmp eq %ow.strmap* %map, null
                  br i1 %absent, label %done, label %retain
                retain:
                  %refs = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = add i32 %before, 1
                  store i32 %after, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_strmap_release(%ow.strmap* %map) {
                entry:
                  %absent = icmp eq %ow.strmap* %map, null
                  br i1 %absent, label %done, label %decrement
                decrement:
                  %refs = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %headPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 0
                  %head = load %ow.strmap.entry*, %ow.strmap.entry** %headPtr
                  br label %loop
                loop:
                  %nodeToFree = phi %ow.strmap.entry* [ %head, %startCleanup ], [ %next, %freeNode ]
                  %empty = icmp eq %ow.strmap.entry* %nodeToFree, null
                  br i1 %empty, label %disposeMap, label %freeNode
                freeNode:
                  %keyPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %nodeToFree, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  call void @__ow_native_string_release(%ow.string %key)
                  %valuePtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %nodeToFree, i32 0, i32 1
                  %value = load %ow.string, %ow.string* %valuePtr
                  call void @__ow_native_string_release(%ow.string %value)
                  %nextPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %nodeToFree, i32 0, i32 2
                  %next = load %ow.strmap.entry*, %ow.strmap.entry** %nextPtr
                  %rawNode = bitcast %ow.strmap.entry* %nodeToFree to i8*
                  call void @free(i8* %rawNode)
                  br label %loop
                disposeMap:
                  %rawMap = bitcast %ow.strmap* %map to i8*
                  call void @free(i8* %rawMap)
                  br label %done
                done:
                  ret void
                }

                define %ow.strmap.entry* @__ow_native_strmap_find(%ow.strmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 0
                  %head = load %ow.strmap.entry*, %ow.strmap.entry** %headPtr
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %node = phi %ow.strmap.entry* [ %head, %entry ], [ %next, %advance ]
                  %empty = icmp eq %ow.strmap.entry* %node, null
                  br i1 %empty, label %missing, label %compare
                compare:
                  %storedPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %storedPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %equal = icmp eq i32 %diff, 0
                  br i1 %equal, label %found, label %advance
                advance:
                  %nextPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 2
                  %next = load %ow.strmap.entry*, %ow.strmap.entry** %nextPtr
                  br label %loop
                found:
                  ret %ow.strmap.entry* %node
                missing:
                  ret %ow.strmap.entry* null
                }

                define void @__ow_native_strmap_set(%ow.strmap* %map, %ow.string %key, %ow.string %value) {
                entry:
                  %old = call %ow.strmap.entry* @__ow_native_strmap_find(%ow.strmap* %map, %ow.string %key)
                  %absent = icmp eq %ow.strmap.entry* %old, null
                  br i1 %absent, label %insert, label %update
                update:
                  %valuePtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %old, i32 0, i32 1
                  %prior = load %ow.string, %ow.string* %valuePtr
                  store %ow.string %value, %ow.string* %valuePtr
                  call void @__ow_native_string_release(%ow.string %prior)
                  call void @__ow_native_string_release(%ow.string %key)
                  br label %done
                insert:
                  %end = getelementptr %ow.strmap.entry, %ow.strmap.entry* null, i32 1
                  %bytes = ptrtoint %ow.strmap.entry* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %store, label %oom
                oom:
                  call void @__ow_native_string_release(%ow.string %key)
                  call void @abort()
                  unreachable
                store:
                  %node = bitcast i8* %raw to %ow.strmap.entry*
                  %keyPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 0
                  store %ow.string %key, %ow.string* %keyPtr
                  %slot = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 1
                  store %ow.string %value, %ow.string* %slot
                  %headPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 0
                  %previous = load %ow.strmap.entry*, %ow.strmap.entry** %headPtr
                  %nextPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 2
                  store %ow.strmap.entry* %previous, %ow.strmap.entry** %nextPtr
                  store %ow.strmap.entry* %node, %ow.strmap.entry** %headPtr
                  %countPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = add i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                define %ow.string @__ow_native_strmap_get(%ow.strmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.strmap.entry* @__ow_native_strmap_find(%ow.strmap* %map, %ow.string %key)
                  %missing = icmp eq %ow.strmap.entry* %found, null
                  br i1 %missing, label %noKey, label %hasKey
                noKey:
                  call void @abort()
                  unreachable
                hasKey:
                  %ptr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %found, i32 0, i32 1
                  %value = load %ow.string, %ow.string* %ptr
                  call void @__ow_native_string_retain(%ow.string %value)
                  ret %ow.string %value
                }

                define i1 @__ow_native_strmap_has(%ow.strmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.strmap.entry* @__ow_native_strmap_find(%ow.strmap* %map, %ow.string %key)
                  %present = icmp ne %ow.strmap.entry* %found, null
                  ret i1 %present
                }

                define i32 @__ow_native_strmap_size(%ow.strmap* %map) {
                entry:
                  %countPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  ret i32 %count
                }

                define void @__ow_native_strmap_delete(%ow.strmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 0
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %link = phi %ow.strmap.entry** [ %headPtr, %entry ], [ %nextLink, %advance ]
                  %node = load %ow.strmap.entry*, %ow.strmap.entry** %link
                  %empty = icmp eq %ow.strmap.entry* %node, null
                  br i1 %empty, label %done, label %compare
                compare:
                  %keyPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %keyPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %same = icmp eq i32 %diff, 0
                  br i1 %same, label %remove, label %advance
                advance:
                  %nextLink = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 2
                  br label %loop
                remove:
                  %nextPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 2
                  %next = load %ow.strmap.entry*, %ow.strmap.entry** %nextPtr
                  store %ow.strmap.entry* %next, %ow.strmap.entry** %link
                  call void @__ow_native_string_release(%ow.string %stored)
                  %valPtr = getelementptr %ow.strmap.entry, %ow.strmap.entry* %node, i32 0, i32 1
                  %val = load %ow.string, %ow.string* %valPtr
                  call void @__ow_native_string_release(%ow.string %val)
                  %rawNode = bitcast %ow.strmap.entry* %node to i8*
                  call void @free(i8* %rawNode)
                  %countPtr = getelementptr %ow.strmap, %ow.strmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = sub i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                """;
    }
}
