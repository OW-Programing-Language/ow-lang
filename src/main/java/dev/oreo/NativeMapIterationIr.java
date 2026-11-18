package dev.oreo;

/**
 * Native snapshots for map_keys/map_values with interpreter-compatible
 * insertion order. Hash buckets are for lookup; an independent doubly
 * linked chain is maintained by NativeHashMapIr for deterministic iteration.
 *
 * Every returned array owns its elements and survives map deletion.
 * Mixed values are stored in a dedicated managed array ABI.
 */
final class NativeMapIterationIr {
    private NativeMapIterationIr() {}

    static String generate() {
        StringBuilder ir = new StringBuilder(mixedArrayRuntime());
        ir.append(fillHelpers("intmap", "i32", null));
        ir.append(fillHelpers("strmap", "%ow.string", "@__ow_native_string_retain"));
        ir.append(fillHelpers("mixedmap", "%ow.mixed", "@__ow_native_mixed_retain"));
        return ir.toString();
    }

    private static String fillHelpers(String kind, String valueType, String retainFn) {
        String template = """
                define void @__ow_native___K___fill_keys(%ow.__K__* %map, %ow.string* %output) {
                entry:
                  %headPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 4
                  %head = load %ow.__K__.entry*, %ow.__K__.entry** %headPtr
                  br label %loop
                loop:
                  %node = phi %ow.__K__.entry* [ %head, %entry ], [ %next, %copy ]
                  %index = phi i32 [ 0, %entry ], [ %nextIndex, %copy ]
                  %empty = icmp eq %ow.__K__.entry* %node, null
                  br i1 %empty, label %done, label %copy
                copy:
                  %keyPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  call void @__ow_native_string_retain(%ow.string %key)
                  %dest = getelementptr %ow.string, %ow.string* %output, i32 %index
                  store %ow.string %key, %ow.string* %dest
                  %orderNextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 4
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %orderNextPtr
                  %nextIndex = add i32 %index, 1
                  br label %loop
                done:
                  ret void
                }

                define void @__ow_native___K___fill_values(%ow.__K__* %map, __V__* %output) {
                entry:
                  %headPtr = getelementptr %ow.__K__, %ow.__K__* %map, i32 0, i32 4
                  %head = load %ow.__K__.entry*, %ow.__K__.entry** %headPtr
                  br label %loop
                loop:
                  %node = phi %ow.__K__.entry* [ %head, %entry ], [ %next, %copy ]
                  %index = phi i32 [ 0, %entry ], [ %nextIndex, %copy ]
                  %empty = icmp eq %ow.__K__.entry* %node, null
                  br i1 %empty, label %done, label %copy
                copy:
                  %valuePtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 1
                  %value = load __V__, __V__* %valuePtr
                  __RETAIN__
                  %dest = getelementptr __V__, __V__* %output, i32 %index
                  store __V__ %value, __V__* %dest
                  %orderNextPtr = getelementptr %ow.__K__.entry, %ow.__K__.entry* %node, i32 0, i32 4
                  %next = load %ow.__K__.entry*, %ow.__K__.entry** %orderNextPtr
                  %nextIndex = add i32 %index, 1
                  br label %loop
                done:
                  ret void
                }

                """;
        return template.replace("__K__", kind)
                .replace("__V__", valueType)
                .replace("__RETAIN__", retainFn == null ? ""
                        : "call void " + retainFn + "(" + valueType + " %value)");
    }

    private static String mixedArrayRuntime() {
        return """
                define %ow.mixedarray @__ow_native_mixedarray_new(i32 %length) {
                entry:
                  %validLength = icmp sge i32 %length, 0
                  br i1 %validLength, label %allocate, label %invalid
                invalid:
                  call void @abort()
                  unreachable
                allocate:
                  %empty = icmp eq i32 %length, 0
                  %slots = select i1 %empty, i32 1, i32 %length
                  %slots64 = zext i32 %slots to i64
                  %end = getelementptr %ow.mixed, %ow.mixed* null, i32 1
                  %elementSize = ptrtoint %ow.mixed* %end to i64
                  %raw = call i8* @calloc(i64 %slots64, i64 %elementSize)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %allocateRefs, label %oom
                oom:
                  call void @abort()
                  unreachable
                allocateRefs:
                  %rawRefs = call i8* @calloc(i64 1, i64 4)
                  %refsValid = icmp ne i8* %rawRefs, null
                  br i1 %refsValid, label %ready, label %refsOom
                refsOom:
                  call void @free(i8* %raw)
                  call void @abort()
                  unreachable
                ready:
                  %data = bitcast i8* %raw to %ow.mixed*
                  %refs = bitcast i8* %rawRefs to i32*
                  store i32 1, i32* %refs
                  %header = insertvalue %ow.mixedarray undef, i32 %length, 0
                  %withData = insertvalue %ow.mixedarray %header, %ow.mixed* %data, 1
                  %result = insertvalue %ow.mixedarray %withData, i32* %refs, 2
                  ret %ow.mixedarray %result
                }

                define void @__ow_native_mixedarray_retain(%ow.mixedarray %array) {
                entry:
                  %refs = extractvalue %ow.mixedarray %array, 2
                  %null = icmp eq i32* %refs, null
                  br i1 %null, label %done, label %retain
                retain:
                  %old = load i32, i32* %refs
                  %updated = add i32 %old, 1
                  store i32 %updated, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_mixedarray_release(%ow.mixedarray %array) {
                entry:
                  %refs = extractvalue %ow.mixedarray %array, 2
                  %null = icmp eq i32* %refs, null
                  br i1 %null, label %done, label %decrement
                decrement:
                  %old = load i32, i32* %refs
                  %updated = sub i32 %old, 1
                  store i32 %updated, i32* %refs
                  %last = icmp eq i32 %updated, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %length = extractvalue %ow.mixedarray %array, 0
                  %data = extractvalue %ow.mixedarray %array, 1
                  br label %loop
                loop:
                  %index = phi i32 [ 0, %startCleanup ], [ %nextIndex, %releaseElement ]
                  %more = icmp ult i32 %index, %length
                  br i1 %more, label %releaseElement, label %freeArray
                releaseElement:
                  %ptr = getelementptr %ow.mixed, %ow.mixed* %data, i32 %index
                  %value = load %ow.mixed, %ow.mixed* %ptr
                  call void @__ow_native_mixed_release(%ow.mixed %value)
                  %nextIndex = add i32 %index, 1
                  br label %loop
                freeArray:
                  %rawData = bitcast %ow.mixed* %data to i8*
                  call void @free(i8* %rawData)
                  %rawRefs = bitcast i32* %refs to i8*
                  call void @free(i8* %rawRefs)
                  br label %done
                done:
                  ret void
                }

                define %ow.mixed @__ow_native_mixedarray_get(%ow.mixedarray %array, i32 %index) {
                entry:
                  %length = extractvalue %ow.mixedarray %array, 0
                  %notNegative = icmp sge i32 %index, 0
                  %beforeEnd = icmp slt i32 %index, %length
                  %valid = and i1 %notNegative, %beforeEnd
                  br i1 %valid, label %read, label %bad
                bad:
                  call void @abort()
                  unreachable
                read:
                  %data = extractvalue %ow.mixedarray %array, 1
                  %ptr = getelementptr %ow.mixed, %ow.mixed* %data, i32 %index
                  %value = load %ow.mixed, %ow.mixed* %ptr
                  call void @__ow_native_mixed_retain(%ow.mixed %value)
                  ret %ow.mixed %value
                }

                """;
    }
}
