# Native OW dictionaries: first milestone

This milestone adds an experimental **string-keyed, integer-valued** dictionary
to the LLVM native backend, without changing the YAML-like OW source syntax.

```ow
fn create():
  returns: int_map
  return: { "Bob": 20, "Alice": 10 }

main:
  set scores: create()
  print: scores["Bob"]
  set scores["Charlie"]: 30
  print: map_size(scores)
```

## Supported in native LLVM

- Dictionary literals with quoted or identifier keys, including `{}`.
- Dynamic string keys, including expressions such as `"é" + "té"`.
- `m[key]` reads, `set m[key]: int` writes or inserts, and aliases to the
  same dictionary preserve mutations.
- `map_size(m)`, `map_has(m,key)`, `map_set(m,key,int)`,
  `map_delete(m,key)` and `map_get(m,key,integerFallback)`.
- `map_get` evaluates its fallback **only if the key is absent**, matching
  the OW interpreter.
- Native `map_has` produces a Boolean printed as `true` or `false`.
- Explicit type annotations `int_map` enable native function parameters
  and returns, using the standard managed-value ownership rules.
- Last-owner reclamation releases each dictionary entry and its dynamically
  allocated string key, then frees the dictionary header.
- A missing-key `m[key]` read terminates the native program rather than
  accessing invalid memory. Native exception support is future work.

## Important limitations

**This is a native integer-dictionary subset, not parity with the full OW
interpreter.** String-valued dictionaries now have a separate experimental
`string_map` ABI; see [native string dictionaries](native-string-dictionaries.md).
Heterogeneous/nested values, objects, null, and floating-point values are
not yet supported by the LLVM backend. The interpreter supports them.

The LLVM implementation currently uses a *linked-list search* over dictionary
entries rather than a hash table. Lookup/insertion is O(n), whereas the
interpreter uses an insertion-ordered hash map with average O(1) lookup.
`map_keys`, `map_values`, `json` and `json_parse` are not yet lowered
natively; native dictionaries cannot be enumerated. Subsequent milestones
will add a hash table and ownership-aware value variants.

Missing keys abort rather than raise OW exceptions. Reference counters are
not atomic, and native dictionaries cannot create reference cycles in this
integer-only subset.

`int_map` is an **experimental LLVM type annotation**; the interpreter
treats these values as normal OW dictionaries. OW's native ABI is not yet
stable across versions.

## Tests

The GitHub Actions workflow builds `examples/native_int_dictionaries.ow`
with LLVM/Clang, compares its output to `ow run`, and runs the native
program under AddressSanitizer/LeakSanitizer with `detect_leaks=1`.
A second executable verifies missing-key indexing exits nonzero.

```sh
ow run examples/native_int_dictionaries.ow
ow llvm examples/native_int_dictionaries.ow target/native-int-dictionaries.ll
clang -g -O0 -fsanitize=address -fno-omit-frame-pointer \
  target/native-int-dictionaries.ll -o target/native-int-dictionaries
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 ./target/native-int-dictionaries
```
