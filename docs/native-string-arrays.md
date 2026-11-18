# Native OW arrays of strings (experimental)

OW can now compile homogeneous **string arrays** to LLVM without a JVM at runtime.

```ow
fn make(count: int):
  returns: string_array
  return: array(count, "Hello " + "OW")

main:
  set names: ["Alice", "Bob", "Charlie"]
  set alias: names
  set names[1]: "OW"
  print: alias[1]
  print: len(names)
  set dynamic: make(3)
  print: dynamic[0]
```

## Types and ownership

An integer array remains `%ow.array` and is unaffected. String arrays use
their own LLVM type `%ow.strarray = { i32 length, %ow.string* data, i32* refs }`.

- Nonempty array literals containing only strings automatically use the
  string-array representation. Empty `[]` still means integer array; use
  `array(0, "")` for an empty string array.
- `array(count, fill)` supports integer or string fill values. The fill
  expression executes once; every string slot owns a retained reference.
- `names[index]`, `set names[index]: expression`, `len(names)`,
  assignments and aliases preserve the existing OW semantics.
- Invalid or negative indexes abort before memory access; negative dynamic
  sizes and allocation failures also abort.
- Each string array is reference counted. Its last owner releases **every**
  child string reference, then frees its data and refcount storage.
- Reassignment retains a borrowed string before releasing the old element,
  so even `set names[0]: names[0]` stays safe.
- Indexing a temporary array retains the result before freeing the container,
  preventing a use-after-free when the indexed string is returned or printed.

## Function boundaries

OW's YAML-style signatures support `string_array` for explicitly typed
**native** string-array parameters and returns. The interpreter accepts top-level
function annotations but does not yet enforce `string_array` as a distinct
runtime subtype; it continues to treat string arrays as ordinary lists.

```ow
fn identity(names: string_array):
  returns: string_array
  return: names
```

This is an *experimental native ABI*, not a stable external FFI type.
Static compiler type inference/generics for `array<string>` are a later milestone.

## Test / limits

The CI workflow compiles `examples/native_string_arrays.ow`, compares native
stdout with `ow run`, and checks the native binary with Clang's
AddressSanitizer/LeakSanitizer (`detect_leaks=1`). Another executable checks that
an out-of-bounds index exits nonzero.

Limitations:
- Arrays must be homogeneous strings or integers. Nested arrays, objects,
  dictionaries, and mixed element types are not yet supported natively.
- The native reference counter is non-atomic: the native collection subset
  does not support concurrent array mutations.
- The runtime has no tracing GC or cycle collector; the restricted native
  string-array element type cannot create reference cycles.
- Unsupported operations remain interpreter-only, including native exceptions,
  OWUnit execution as native binaries and full Night/web/object semantics.
