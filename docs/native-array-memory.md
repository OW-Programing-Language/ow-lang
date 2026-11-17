# Native integer array memory management (experimental)

The LLVM backend uses **reference counting** for heap-backed, 32-bit-integer
arrays. Each array value stores its length, an allocated data pointer, and a
pointer to a shared reference counter.

## Implemented

- A newly created array starts with reference count 1.
- Assigning a variable to an existing array retains the reference.
- Reassigning a variable releases its previous reference, *after* retaining
  the new one (safe for self-assignment and shared aliases).
- Entering a branch or loop does not require array locals to have been
  assigned yet; array slots are initialized to a null/zero header at entry.
- Array locals are released on return or when a function/main ends.
- Temporary `len(array(...))` and `array(...)[index]` values are released
  after use, as are unused `call: array(...)` results.
- The final reference frees both the integer-element backing store and its
  reference-count allocation.

## Validation

The `native-arrays` workflow runs the JVM-based OW interpreter and a
Clang-compiled executable for the same `examples/native_array_memory.ow`
program, compares their output, then builds and runs the native program with
AddressSanitizer and leak detection.

```sh
ow llvm examples/native_array_memory.ow target/native-array-memory.ll
clang -fsanitize=address -fno-omit-frame-pointer -g \
  target/native-array-memory.ll -o target/native-array-memory-asan
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 ./target/native-array-memory-asan
```

## Explicit limitations

- This is a focused first version of ARC, **not a full garbage collector**.
- Native arrays support **explicitly typed** function parameters and
  return values with ownership transfer. See [native array functions](native-array-functions.md).
- Only 32-bit integer array elements are supported; arbitrary objects and
  nested arrays remain interpreter-only.
- Reference counts are non-atomic (OW's native array subset is single-threaded).
- All array references must be represented through compiler-managed array
  variables or temporary expressions. Native FFI and foreign holders are not
  supported.
- No cyclic array references exist in this `i32`-only subset. For general OW
  objects and containers, cycle handling will require a broader ownership/GC
  design.
- Native bounds errors and allocation failures still abort the process, rather
  than raising OW exceptions.

See [native arrays](native-arrays.md) for syntax and earlier milestones.
