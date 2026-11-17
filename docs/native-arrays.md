# Native LLVM integer arrays: literals and runtime-sized arrays

The LLVM backend now lowers **integer-array literals** using a two-field
`%ow.array` header: a 32-bit length and a pointer to shared heap backing
storage. This is an incremental native feature with **reference-counted** lifetime
management for the native i32 array subset. It is not a full garbage collector.

Supported natively:
- `set items: [1, 2, 3]` and `set empty: []`
- `array(size, fill)` where `size` and `fill` evaluate to 32-bit integers.
  The size may be calculated at runtime, and `fill` is evaluated exactly once.
  Negative sizes fail before evaluating the fill value.
- `len(items)` with one integer-array argument
- `items[index]` and `set items[index]: value`
- Copying array variables aliases the shared storage, matching the interpreter
- Negative and out-of-range indexes are checked before accessing memory.
  Bounds violations terminate with an abort rather than touching invalid memory.
- Allocation failure also aborts.

Example:

```ow
main:
  set values: [10, 20, 30]
  set another: values
  set values[1]: 99
  print: len(another)
  print: another[1]
```

```sh
ow run examples/native_arrays.ow
ow llvm examples/native_arrays.ow native-arrays.ll
clang native-arrays.ll -o native-arrays
./native-arrays
```

### Runtime-sized array example

```ow
main:
  set size: 2 + 2
  set items: array(size, 7)
  print: len(items)
  print: items[0]
```

```sh
ow run examples/native_dynamic_arrays.ow
ow llvm examples/native_dynamic_arrays.ow target/native-dynamic-arrays.ll
clang target/native-dynamic-arrays.ll -o target/native-dynamic-arrays
./target/native-dynamic-arrays
```

The native code checks for negative array lengths and failed allocations
before accessing memory. Runtime-sized arrays use a generated loop that
fills each 32-bit element and allocates at least one slot for empty arrays.

### Current limitations and future work

- Arrays are restricted to **32-bit integer elements**. Mixed arrays,
  strings, nested arrays, and dictionaries are not supported by this lowering.
- Passing and returning arrays across functions are not yet supported in native mode.
- **Native i32 array allocations now use reference counting.** Shared aliases
  keep backing storage alive; the last owner releases it. This is not yet
  general memory management for nested arrays, objects or cycles. See
  [native array memory management](native-array-memory.md) for details and
  AddressSanitizer validation.
- Bounds and allocation failures abort rather than raising OW exceptions;
  native exception integration is a separate milestone.
- The interpreter remains the reference implementation for full OW semantics.

The integration workflow compiles both array examples using the JVM
interpreter and LLVM/Clang and compares their outputs. Out-of-range indexing
and negative dynamic sizes are separately checked for nonzero native exit status.
