# Native functions with integer-array parameters and returns

OW's LLVM backend supports an **experimental monomorphic function ABI** for
32-bit integer arrays. Type declarations are required at native function
boundaries because there is no general static type inference yet.

```ow
fn make(size int, fill int) -> array:
  return: array(size, fill)

fn identity(values array) -> array:
  return: values

main:
  set values: identity(make(3, 9))
  print: values[0]
```

## Conventions

- `fn name(values array) -> array:` explicitly describes a native array
  parameter and array return value.
- Untyped parameters and returns remain `i32` in the current native
  compiler. `int` and `bool` also lower to `i32`.
- All array arguments pass ownership of **one reference** into the callee.
  A borrowed local is retained before the call. A newly created or returned
  temporary array is moved directly into the callee.
- Callees always release array parameter references when returning.
- For `return: values`, a borrowed array is retained **before** local
  cleanup, then the owned result is returned to the caller. For a fresh array,
  ownership is simply transferred without a redundant retain.
- The caller owns any array returned by a native function. It must either
  store, forward, or consume and release that returned reference.
- Array-returning functions require an explicit final `return:` statement,
  so control cannot reach a generated implicit null-array return.
- The compiler rejects calls with incompatible argument types or arities.

## Validate

The `native-arrays` workflow compares interpreted and natively compiled
output for `examples/native_array_functions.ow`, then runs the LLVM binary
with Clang AddressSanitizer and leak detection.

```sh
ow llvm examples/native_array_functions.ow target/array-functions.ll
clang -g -O0 -fsanitize=address -fno-omit-frame-pointer \
  target/array-functions.ll -o target/array-functions
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 ./target/array-functions
```

## Limitations

This is a small incremental compiler feature, **not** a full runtime:
- Only integer arrays (`%ow.array`) are supported, not nested/mixed arrays.
- Array types must be explicit in native function signatures. No overloads,
  generics, inference or cross-module ABI stability guarantees yet.
- Native types other than `int`, `bool` and `array` are rejected.
- Native exception unwinding and GC for object graphs remain unimplemented.
- Native reference counting is single-threaded and does not handle cycles.
- As before, non-array values still have the original procedural backend
  limitations. The interpreter remains the full-language reference.
