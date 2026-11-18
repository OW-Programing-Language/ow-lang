package dev.oreo;

/**
 * Hash-bucket implementation shared by all three experimental OW native maps.
 * All maps use the same FNV-1a UTF-8 hash and power-of-two capacities, while
 * preserving their distinct value representations and ownership rules.
 *
 * Buckets are rehashed on growth (>75% load), with separate-chaining for
 * collisions. A map's final owner releases all bucket entries and keys.
 * String or mixed values are also released before their nodes are freed.
 */
final class NativeHashMapIr {
    private NativeHashMapIr() {}

    static String hashHelper() {
        return """
                define i64 @__ow_native_hash_utf8(%ow.string %key) {
                entry:
                  %start = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %cursor = phi i8* [ %start, %entry ], [ %next, %advance ]
                  %hash = phi i64 [ -3750763034362895579, %entry ], [ %multiplied, %advance ]
                  %byte = load i8, i8* %cursor
                  %end = icmp eq i8 %byte, 0
                  br i1 %end, label %done, label %advance
                advance:
                  %unsigned = zext i8 %byte to i64
                  %xored = xor i64 %hash, %unsigned
                  %multiplied = mul i64 %xored, 1099511628211
                  %next = getelementptr i8, i8* %cursor, i64 1
                  br label %loop
                done:
                  ret i64 %hash
                }

                """;
    }

    static String generate(String name, String valueType, String retainFn, String releaseFn) {
        if (!name.equals("intmap") && !name.equals("strmap") && !name.equals("mixedmap")) {
            throw new IllegalArgumentException("Unsupported native map: " + name);
        }
        String source = """
                define %ow.__K__* @__ow_native___K___new() {
                entry:
                  %end = getelementptr %ow.__K__, %ow.__K__* null, i32 1
                  %bytes = ptrtoint %ow.__K__* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %allocateBuckets, label %oom
                oom:
                  call void @abort()
                  unreachable
                allocateBuckets:
                  %map = bitcast i8* %raw to %ow.__K__*
                  %pointerEnd = getelementptr %ow.__K__.entry*, %ow.__K__.entry** null, i32 1
                  %pointerBytes = ptrtoint %ow.__K__.entry** %pointerEnd to i64
                  %bucketRaw = call i8* @calloc(i64 16, i64 %pointerBytes)
                  %bucketsValid = icmp ne i8* %bucketRaw, null
                  br i1 %bucketsValid, label %created, label %bucketOom
                bucketOom:
                  call void @free(i8* %raw)
                  call void @abort()
                  unreachable
                created:
                  %buckets = bitcast i8* %bucketRaw to %ow.__K__.entry**
                  %bucketsPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 0
                  store %ow.__K__.entry** %buckets, %ow.__K__.entry*** %bucketsPtr
                  %refs = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 1
                  store i32 1, i32* %refs
                  %capacityPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 3
                  store i32 16, i32* %capacityPtr
                  ret %ow.__K__* %map
                }

                define void @__ow_native___K___retain(%ow.__K__* %map) {
                entry:
                  %absent = icmp eq %ow.__K__* %map, null
                  br i1 %absent, label %done, label %retain
                retain:
                  %refs = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = add i32 %before, 1
                  store i32 %after, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native___K___release(%ow.__K__* %map) {
                entry:
                  %absent = icmp eq %ow.__K__* %map, null
                  br i1 %absent, label %done, label %decrement
                decrement:
                  %refs = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %bucketsPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 0
                  %buckets = load %ow.__K__.entry**, %ow.__K__.entry*** %bucketsPtr
                  %capacityPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 3
                  %capacity = load i32, i32* %capacityPtr
                  br label %visitBucket
                visitBucket:
                  %i = phi i32 [ 0, %startCleanup ], [ %nextIndex, %afterBucket ]
                  %hasBucket = icmp ult i32 %i, %capacity
                  br i1 %hasBucket, label %loadBucket, label %disposeMap
                loadBucket:
                  %slot = getelementptr %ow.__K__.entry*, %ow.__K__.entry** %buckets, i32 %i
                  %head = load %ow.__K__.entry*, %ow.__K__.entry** %slot
                  br label %loop
                loop:
                  %nodeToFree = phi %ow.__K__.entry* [ %head, %loadBucket ], [ %next, %freeNode ]
                  %empty = icmp eq %ow.__K__.entry* %nodeToFree, null
                  br i1 %empty, label %afterBucket, label %freeNode
                freeNode:
                  %keyPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %nodeToFree, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  call void @__ow_native_string_release(%ow.string %key)
                  %valuePtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %nodeToFree, i32 0, i32 1
                  %value = load __V__, __V__* %valuePtr
                  __RELEASE_VALUE__
                  %nextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %nodeToFree, i32 0, i32 2
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %nextPtr
                  %rawNode = bitcast %ow.__K__.entry* %nodeToFree to i8*
                  call void @free(i8* %rawNode)
                  br label %loop
                afterBucket:
                  %nextIndex = add i32 %i, 1
                  br label %visitBucket
                disposeMap:
                  %rawBuckets = bitcast %ow.__K__.entry** %buckets to i8*
                  call void @free(i8* %rawBuckets)
                  %rawMap = bitcast %ow.__K__* %map to i8*
                  call void @free(i8* %rawMap)
                  br label %done
                done:
                  ret void
                }

                define %ow.__K__.entry** @__ow_native___K___bucket_slot(%ow.__K__* %map, %ow.string %key) {
                entry:
                  %bucketsPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 0
                  %buckets = load %ow.__K__.entry**, %ow.__K__.entry*** %bucketsPtr
                  %capacityPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 3
                  %capacity = load i32, i32* %capacityPtr
                  %hash = call i64 @__ow_native_hash_utf8(%ow.string %key)
                  %mask32 = sub i32 %capacity, 1
                  %mask = zext i32 %mask32 to i64
                  %slotIndex64 = and i64 %hash, %mask
                  %slotIndex = trunc i64 %slotIndex64 to i32
                  %slot = getelementptr %ow.__K__.entry*, %ow.__K__.entry** %buckets, i32 %slotIndex
                  ret %ow.__K__.entry** %slot
                }

                define %ow.__K__.entry* @__ow_native___K___find(%ow.__K__* %map, %ow.string %key) {
                entry:
                  %slot = call %ow.__K__.entry** @__ow_native___K___bucket_slot(%ow.__K__* %map, %ow.string %key)
                  %head = load %ow.__K__.entry*, %ow.__K__.entry** %slot
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %node = phi %ow.__K__.entry* [ %head, %entry ], [ %next, %advance ]
                  %empty = icmp eq %ow.__K__.entry* %node, null
                  br i1 %empty, label %missing, label %compare
                compare:
                  %storedPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %storedPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %equal = icmp eq i32 %diff, 0
                  br i1 %equal, label %found, label %advance
                advance:
                  %nextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 2
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %nextPtr
                  br label %loop
                found:
                  ret %ow.__K__.entry* %node
                missing:
                  ret %ow.__K__.entry* null
                }

                define void @__ow_native___K___rehash(%ow.__K__* %map) {
                entry:
                  %bucketsPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 0
                  %oldBuckets = load %ow.__K__.entry**, %ow.__K__.entry*** %bucketsPtr
                  %capPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 3
                  %oldCapacity = load i32, i32* %capPtr
                  %canGrow = icmp ult i32 %oldCapacity, 1073741824
                  br i1 %canGrow, label %allocate, label %tooLarge
                tooLarge:
                  call void @abort()
                  unreachable
                allocate:
                  %capacity = shl i32 %oldCapacity, 1
                  %pointerEnd = getelementptr %ow.__K__.entry*, %ow.__K__.entry** null, i32 1
                  %pointerBytes = ptrtoint %ow.__K__.entry** %pointerEnd to i64
                  %newSlots = zext i32 %capacity to i64
                  %raw = call i8* @calloc(i64 %newSlots, i64 %pointerBytes)
                  %valid = icmp ne i8* %raw, null
                  br i1 %valid, label %switch, label %oom
                oom:
                  call void @abort()
                  unreachable
                switch:
                  %newBuckets = bitcast i8* %raw to %ow.__K__.entry**
                  store %ow.__K__.entry** %newBuckets, %ow.__K__.entry*** %bucketsPtr
                  store i32 %capacity, i32* %capPtr
                  br label %bucketLoop
                bucketLoop:
                  %i = phi i32 [ 0, %switch ], [ %nextIndex, %nextBucket ]
                  %hasBucket = icmp ult i32 %i, %oldCapacity
                  br i1 %hasBucket, label %readBucket, label %dispose
                readBucket:
                  %oldSlot = getelementptr %ow.__K__.entry*, %ow.__K__.entry** %oldBuckets, i32 %i
                  %head = load %ow.__K__.entry*, %ow.__K__.entry** %oldSlot
                  br label %walk
                walk:
                  %node = phi %ow.__K__.entry* [ %head, %readBucket ], [ %next, %moveNode ]
                  %missing = icmp eq %ow.__K__.entry* %node, null
                  br i1 %missing, label %nextBucket, label %moveNode
                moveNode:
                  %nextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 2
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %nextPtr
                  %keyPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  %slot = call %ow.__K__.entry** @__ow_native___K___bucket_slot(%ow.__K__* %map, %ow.string %key)
                  %prior = load %ow.__K__.entry*, %ow.__K__.entry** %slot
                  store %ow.__K__.entry* %prior, %ow.__K__.entry** %nextPtr
                  store %ow.__K__.entry* %node, %ow.__K__.entry** %slot
                  br label %walk
                nextBucket:
                  %nextIndex = add i32 %i, 1
                  br label %bucketLoop
                dispose:
                  %oldRaw = bitcast %ow.__K__.entry** %oldBuckets to i8*
                  call void @free(i8* %oldRaw)
                  ret void
                }

                define void @__ow_native___K___set(%ow.__K__* %map, %ow.string %key, __V__ %value) {
                entry:
                  %old = call %ow.__K__.entry* @__ow_native___K___find(%ow.__K__* %map, %ow.string %key)
                  %absent = icmp eq %ow.__K__.entry* %old, null
                  br i1 %absent, label %insert, label %update
                update:
                  %valuePtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %old, i32 0, i32 1
                  %prior = load __V__, __V__* %valuePtr
                  store __V__ %value, __V__* %valuePtr
                  __RELEASE_PRIOR__
                  call void @__ow_native_string_release(%ow.string %key)
                  br label %done
                insert:
                  %end = getelementptr %ow.__K__.entry, %ow.__K__.entry* null, i32 1
                  %bytes = ptrtoint %ow.__K__.entry* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %store, label %oom
                oom:
                  call void @__ow_native_string_release(%ow.string %key)
                  call void @abort()
                  unreachable
                store:
                  %node = bitcast i8* %raw to %ow.__K__.entry*
                  %keyPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 0
                  store %ow.string %key, %ow.string* %keyPtr
                  %slot = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 1
                  store __V__ %value, __V__* %slot
                  %bucketSlot = call %ow.__K__.entry** @__ow_native___K___bucket_slot(%ow.__K__* %map, %ow.string %key)
                  %priorNode = load %ow.__K__.entry*, %ow.__K__.entry** %bucketSlot
                  %nextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 2
                  store %ow.__K__.entry* %priorNode, %ow.__K__.entry** %nextPtr
                  store %ow.__K__.entry* %node, %ow.__K__.entry** %bucketSlot
                  %countPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = add i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  %capPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 3
                  %cap = load i32, i32* %capPtr
                  %count64 = zext i32 %countNext to i64
                  %cap64 = zext i32 %cap to i64
                  %loadTimesFour = mul i64 %count64, 4
                  %capTimesThree = mul i64 %cap64, 3
                  %grow = icmp ugt i64 %loadTimesFour, %capTimesThree
                  br i1 %grow, label %resize, label %done
                resize:
                  call void @__ow_native___K___rehash(%ow.__K__* %map)
                  br label %done
                done:
                  ret void
                }

                define __V__ @__ow_native___K___get(%ow.__K__* %map, %ow.string %key) {
                entry:
                  %found = call %ow.__K__.entry* @__ow_native___K___find(%ow.__K__* %map, %ow.string %key)
                  %missing = icmp eq %ow.__K__.entry* %found, null
                  br i1 %missing, label %noKey, label %hasKey
                noKey:
                  call void @abort()
                  unreachable
                hasKey:
                  %ptr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %found, i32 0, i32 1
                  %value = load __V__, __V__* %ptr
                  __RETAIN_RESULT__
                  ret __V__ %value
                }

                define i1 @__ow_native___K___has(%ow.__K__* %map, %ow.string %key) {
                entry:
                  %found = call %ow.__K__.entry* @__ow_native___K___find(%ow.__K__* %map, %ow.string %key)
                  %present = icmp ne %ow.__K__.entry* %found, null
                  ret i1 %present
                }

                define i32 @__ow_native___K___size(%ow.__K__* %map) {
                entry:
                  %countPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  ret i32 %count
                }

                define void @__ow_native___K___delete(%ow.__K__* %map, %ow.string %key) {
                entry:
                  %link = call %ow.__K__.entry** @__ow_native___K___bucket_slot(%ow.__K__* %map, %ow.string %key)
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %pointer = phi %ow.__K__.entry** [ %link, %entry ], [ %nextLink, %advance ]
                  %node = load %ow.__K__.entry*, %ow.__K__.entry** %pointer
                  %empty = icmp eq %ow.__K__.entry* %node, null
                  br i1 %empty, label %done, label %compare
                compare:
                  %keyPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %keyPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %same = icmp eq i32 %diff, 0
                  br i1 %same, label %remove, label %advance
                advance:
                  %nextLink = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 2
                  br label %loop
                remove:
                  %nextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 2
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %nextPtr
                  store %ow.__K__.entry* %next, %ow.__K__.entry** %pointer
                  call void @__ow_native_string_release(%ow.string %stored)
                  %valPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 1
                  %val = load __V__, __V__* %valPtr
                  __RELEASE_VAL__
                  %rawNode = bitcast %ow.__K__.entry* %node to i8*
                  call void @free(i8* %rawNode)
                  %countPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = sub i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                """;
        String relPrior = releaseFn == null ? "" : "call void " + releaseFn + "(" + valueType + " %prior)";
        String relValue = releaseFn == null ? "" : "call void " + releaseFn + "(" + valueType + " %value)";
        String relVal = releaseFn == null ? "" : "call void " + releaseFn + "(" + valueType + " %val)";
        String retainResult = retainFn == null ? "" : "call void " + retainFn + "(" + valueType + " %value)";
        String ir = source.replace("__K__", name)
                .replace("__V__", valueType)
                .replace("__RELEASE_PRIOR__", relPrior)
                .replace("__RELEASE_VALUE__", relValue)
                .replace("__RELEASE_VAL__", relVal)
                .replace("__RETAIN_RESULT__", retainResult);
        if (name.equals("mixedmap")) {
            // Mixed maps can own other mixed maps. Refuse both direct and
            // transitive self-cycles before writing the new reference.
            // All accepted container graphs therefore remain acyclic and
            // can be reclaimed recursively by reference counting.
            String setEntry = "define void @__ow_native_mixedmap_set(%ow.mixedmap* %map, "
                    + "%ow.string %key, %ow.mixed %value) {\\n"
                    + "entry:\\n";
            String check = "  call void @__ow_native_nested_guard_cycle(%ow.mixedmap* "
                    + "%map, %ow.mixed %value)\\n";
            if (!ir.contains(setEntry)) {
                throw new IllegalStateException("Native mixed-map setter ABI changed");
            }
            ir = ir.replace(setEntry, setEntry + check);
        }
        return ir;
    }
}
