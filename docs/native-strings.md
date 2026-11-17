# Native OW UTF-8 string subset (experimental)

OW's LLVM backend supports **UTF-8 strings** in native function signatures,
`==` / `!=` content comparison, and `len` with OW-compatible Java-style
UTF-16 code-unit counting. It also supports dynamic `string + string`
concatenation and reference-counted heap-backed strings. See
[native dynamic strings](native-dynamic-strings.md) for ownership details.

```ow
fn identity(value string) -> string:
  return: value

main:
  set message: identity("hé🙂")
  print: message
  print: len(message)
```

Unlike C's `strlen` (which counts bytes), `len("hé🙂")` returns **4**:
the accented `é` is one UTF-16 code unit and the non-BMP emoji is two.
The interpreter uses Java `String.length()`, so this distinction matters
for interpreter/native output parity.

## Implementation

- String constants are encoded as UTF-8 zero-terminated immutable globals.
  Native string values use `%ow.string = {i8* data, i32* refs}`:
  static literals have a null refcount pointer, dynamically concatenated
  values have allocated refcounts.
- Native functions accept `fn identity(value string) -> string:`.
  The compiler validates argument and return types.
- Comparisons call libc `strcmp`, which compares the **contents**, not
  the addresses, of the strings.
- The generated LLVM UTF-8 scanner counts UTF-16 code units for valid,
  null-terminated UTF-8 source strings.
- Dynamically concatenated strings use checked allocation and
  reference counting, so local variables, aliases, nested temporaries,
  and function returns release allocations when no longer owned.

## Limitations

- **Dynamic `string + string` is supported**, but native string builders,
  input strings, string arrays and dictionaries are not supported yet.
  A length-aware string representation is still planned.
- Embedded NUL characters are rejected by the compiler because C-string
  `strcmp` and the scanner would otherwise silently truncate the value.
- Strings are immutable. They can originate from source literals or dynamic
  concatenation. External foreign pointers and invalid UTF-8 are outside
  the supported subset.
- Native string `==` and `!=` support string-to-string comparisons only.
  Mixed-type string/number comparisons are not yet available; the interpreter
  permits some conversions.
- Comparisons such as `<` and `>` on strings remain unsupported, matching
  the OW interpreter's string comparison rules.
- A native string-returning function must end with an explicit `return:`.
- The current native compiler toolchain is still Java-hosted, but compiled
  executables do not need a JVM.

Run the native integration test in GitHub Actions or locally:

```sh
ow llvm examples/native_strings.ow target/native-strings.ll
clang target/native-strings.ll -o target/native-strings
./target/native-strings
```

The `native-strings` CI job compares this executable's output against the
JVM interpreter, including non-ASCII and non-BMP characters.
