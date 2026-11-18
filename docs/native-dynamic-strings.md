# Native dynamic UTF-8 strings (experimental)

OW's LLVM backend now supports **`string + string`**, including strings
created at runtime and passed between typed functions.

```ow
fn greet(name string) -> string:
  return: "Hello " + name

main:
  set value: greet("OW")
  print: value
```

## Representation and memory lifetime

Native `string` values are represented as `%ow.string = type { i8*, i32* }`:
a pointer to UTF-8 bytes and an optional pointer to a reference count.

- **Literals** have immutable static bytes and a null reference-count pointer.
  They require no heap allocation or cleanup.
- **Concatenations** allocate UTF-8 bytes and a reference count initialized
  to one, with checked allocation and overflow paths.
- Assigning a borrowed string variable, or passing one as a function argument,
  retains the reference; the last owner releases the bytes and refcount.
- Overwriting a string, exiting a function, returning an owned value, or
  discarding a computed string releases the appropriate ownership references.
- Concatenation consumes its operands without changing their values: owned
  temporary operands are released after the new string is constructed.
- String parameters and returns transfer one reference using the same
  conventions as native integer arrays; source literals are immortal.
- `len(string)` counts UTF-16 code units, matching the current OW
  interpreter even for Unicode supplementary characters.

## Test

The `native-strings` GitHub Actions workflow compiles
`examples/native_dynamic_strings.ow` with LLVM/Clang, compares its output
with the interpreter, then executes an AddressSanitizer+LeakSanitizer build
with `detect_leaks=1` to detect leaks and invalid access.

## Current limitations

- Native strings use null-terminated UTF-8 storage. Embedded NUL literals
  are rejected; a length-aware representation is still needed.
- Only **string + string** is supported natively. The interpreter's
  mixed-type concatenation (such as `"x" + 42`) requires a separate
  conversion/type-lowering milestone.
- Strings are immutable and single-threaded; the native reference counter
  is non-atomic. Native string arrays are now supported in the experimental
  [string-array backend](native-string-arrays.md), but dictionaries are not yet native.
- Native exception handling and a general object garbage collector remain
  future work.
- This feature changes the experimental native string ABI from plain
  `i8*` to `%ow.string`. There is no stable external native ABI promise.
