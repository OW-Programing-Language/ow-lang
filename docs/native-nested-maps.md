# Native nested dictionaries in OW (experimental)

The LLVM backend can now store native dictionaries **inside other dictionaries**,
using the same YAML-like OW syntax as the interpreter.

```ow
fn configuration():
  returns: mixed_map
  return: { database: { host: "localhost", port: 5432 }, version: 6 }

main:
  set config: configuration()
  print: config["database"]["port"]
  print: config["database"]["host"]
```

## Type representation and ownership

The native tagged-value ABI becomes
`%ow.mixed = { i32 tag, i32 number, %ow.string string, i8* map }`.
Tags are: 0 = integer, 1 = UTF-8 string, 2 = `int_map`, 3 = `string_map`,
4 = `mixed_map`.

A map that stores any map-typed value automatically selects the `mixed_map`
representation. An integer-only map still uses `int_map` and a string-only
map still uses `string_map`. Unchanged scalar maps preserve their previous
source behavior.

- Each parent map entry owns one ARC reference to its child map.
- Borrowed child references are retained when boxed. New child temporaries
  transfer their owned reference directly into their parent.
- Indexing nested maps returns an owned tagged result. It is safe to release
  temporary parents or function arguments immediately after an indexed read.
- Deleting an entry, overwriting it with a different value type, or releasing
  the final owner recursively releases all descendant map references and
  dynamically allocated UTF-8 keys and values.
- **Cycles are not silently accepted.** Every mixed-map insertion checks
  whether the proposed child can reach its parent. Direct and transitive
  cycles abort the program; accepted nested graphs are DAGs and can be
  reclaimed by reference counting.
- Lookup continues to use the resizing hash-table runtime from PR #58,
  with average O(1) map-key operations (worst case O(n) for collisions).

## Supported operations

Nested indexing works for `int_map`, `string_map`, and `mixed_map` children:

```ow
main:
  set data: { ints: { port: 5432 }, text: { name: "OW" } }
  print: data["ints"]["port"]
  print: data["text"]["name"]
```

The outer `mixed_map` supports direct `set data["child"]: anotherMap`,
`map_set`, `map_get` with lazy fallback, `map_delete`, `map_has`, and
`map_size`. Existing strongly typed map-function parameters remain valid.
The first release supports **reading** through arbitrary nested depths;
nested `set data["x"]["y"]: ...` syntax and applying `map_size` directly
to a dynamically tagged child are future milestones.

## Limits

- Nested map **values** are supported; arrays as nested map values, object
  graphs, floats, null and boolean map values remain interpreter-only.
- `print` on a *tagged scalar* still prints integers and strings. Printing
  an entire tagged map is not yet supported natively; it aborts explicitly,
  rather than misleadingly showing `0`.
- The runtime is single-threaded. There is no tracing garbage collector;
  cycles are rejected rather than collected.
- Native missing keys and scalar-as-map indexing abort rather than raising a
  catchable OW exception. Arbitrarily deep DAGs can exhaust the native call
  stack during recursive destruction or cycle checking; depth limits are a
  future hardening task.
- The tagged mixed-value ABI changed. Existing compiled native binaries must
  be rebuilt after this PR. The native ABI remains experimental.

## Validation

GitHub CI executes `examples/native_nested_maps.ow` in both interpreter and
native LLVM+Clang modes, checks exact output, and runs ASan/LSan with
`detect_leaks=1`. Negative compiled fixtures check direct and indirect
cycles, missing keys and indexing a scalar as a map.
