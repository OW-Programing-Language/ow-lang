# Native OW UTF-8 string subset (experimental)

OW's LLVM backend now supports **immutable UTF-8 strings** in native
function signatures, `==` / `!=` content comparison, and `len` with
OW-compatible Java-style UTF-16 code-unit counting.

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

- String constants are encoded as UTF-8 zero-terminated immutable globals,
  represented as LLVM `i8*`.
- Native functions accept `fn identity(value string) -> string:`.
  The compiler validates argument and return types.
- Comparisons call libc `strcmp`, which compares the **contents**, not
  the addresses, of the strings.
- The generated LLVM UTF-8 scanner counts UTF-16 code units for valid,
  null-terminated UTF-8 source strings.
- No heap allocation or string ownership mechanism is needed for this
  first immutable-literal-only subset; string values may be passed between
  native functions and stored in local variables.

## Limitations

- **No dynamic string concatenation, string builders, input strings or string
  arrays yet.** A later milestone must introduce a length-aware owned string
  representation and memory management, then extend it to collection elements.
- Embedded NUL characters are rejected by the compiler because C-string
  `strcmp` and the scanner would otherwise silently truncate the value.
- Strings are immutable and originate from source literals or functions
  forwarding them. External foreign pointers and invalid UTF-8 are outside
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
