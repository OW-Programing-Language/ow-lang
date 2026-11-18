package dev.oreo;

/** Native string-valued dictionaries use the shared hash-table runtime.
 * Each node still owns both its key and managed string value.
 */
final class NativeStringMapIr {
    private NativeStringMapIr() {}

    static String generate() {
        return NativeHashMapIr.generate("strmap", "%ow.string",
                "@__ow_native_string_retain", "@__ow_native_string_release");
    }
}
