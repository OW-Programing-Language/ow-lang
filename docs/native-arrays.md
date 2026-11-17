# Native LLVM arrays: initial milestone

The LLVM backend now lowers **integer-array literals** using a two-field
`%ow.array` header: a 32-bit length and a pointer to shared heap backing
storage. This is an incremental native feature, **not yet a complete
memory-managed array implementation**.

Supported natively:
- `set items: [1, 2, 3]` and `set empty: []`
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

### Current limitations and future work

- Arrays are restricted to **32-bit integer elements**. Mixed arrays,
  strings, nested arrays, and dictionaries are not supported by this lowering.
- `array(count, fill)` and passing/returning arrays across functions are
  not yet supported in native mode.
- **Native array allocations are not reclaimed yet.** This milestone
  deliberately uses `calloc` with shared mutable backing storage, but
  a proper native ownership/GC strategy is still required before long-running
  workloads can use these arrays safely without memory growth.
- Bounds and allocation failures abort rather than raising OW exceptions;
  native exception integration is a separate milestone.
- The interpreter remains the reference implementation for full OW semantics.

The integration workflow compiles the same sample using both the JVM
interpreter and LLVM/Clang and compares their outputs. Out-of-range indexing
is separately checked for a nonzero native exit status.
