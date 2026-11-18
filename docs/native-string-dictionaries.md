# Native string-valued OW dictionaries (experimental)

This milestone extends the native LLVM dictionary runtime with **UTF-8 string
keys and string values**, preserving YAML-style map literals.

```ow
fn make():
  returns: string_map
  return: { name: "OW", greeting: "Hello " + "OW" }

main:
  set config: make()
  print: config["greeting"]
  set config["name"]: "Oreo Studios"
  print: map_size(config)
```

The existing `int_map` representation and integer dictionary behavior are
unchanged. Nonempty map literals choose `int_map` or `string_map` from their
first value. Mixing value types within the same native map fails compilation.

## Memory ownership

Native `string_map` values use a reference-counted `%ow.strmap*` pointer.
Entries hold **two managed strings**, the key and the value.

- Creating a node transfers one owned reference for each key and value.
- Replacing a value stores a retained/owned replacement **before** freeing
  the old one, including self-replacement and string aliases.
- Deleting a node or releasing the last dictionary reference frees both
  managed strings and the node.
- Indexed reads retain the returned string before the caller or any
  temporary dictionary can be released.
- `map_get` returns an owned string and evaluates its default **lazily**,
  only when the key is absent.
- `map_set` and `map_delete` mutate and return the original map, keeping
  shared aliases consistent.

## Operations

The native subset includes `m[key]`, `set m[key]: value`, `map_size`,
`map_has`, `map_get`, `map_set` and `map_delete` for both supported
homogeneous map types. Explicit `string_map` annotations allow map parameters
and returns in LLVM-compiled functions.

**Limitations:** An empty `{}` defaults to `int_map`. To create an empty
string map, start with a nonempty string-valued literal and delete its last
entry; a typed empty-map constructor is planned. This backend currently uses
a shared [resizing hash-table runtime](native-hash-dictionaries.md),
with average O(1) lookup, insertion and deletion. `map_keys`, `map_values`,
`json`, nested maps and object graphs remain unsupported. Mixed integer/string
values are now available using a separate `mixed_map` ABI. The interpreter supports those features.

Native missing-key indexing terminates the process (instead of raising a
catchable OW exception). Reference counters are non-atomic, so these maps
are not a cross-thread runtime.

## Validation

`examples/native_string_dictionaries.ow` runs through both the JVM
interpreter and the native LLVM backend; CI compares stdout byte-for-byte
and checks native allocations with AddressSanitizer + LeakSanitizer.
`examples/native_string_dictionaries_missing.ow` checks missing-key aborts.
