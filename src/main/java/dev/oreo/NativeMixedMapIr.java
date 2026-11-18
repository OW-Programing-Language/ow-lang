package dev.oreo;

/** Mixed integer/UTF-8 dictionaries share the native hash-bucket runtime.
 * Tagged string values are retained/released by the existing helper ABI.
 */
final class NativeMixedMapIr {
    private NativeMixedMapIr() {}

    static String generate() {
        return NativeHashMapIr.generate("mixedmap", "%ow.mixed",
                "@__ow_native_mixed_retain", "@__ow_native_mixed_release")
                + mixedHelpers();
    }

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
