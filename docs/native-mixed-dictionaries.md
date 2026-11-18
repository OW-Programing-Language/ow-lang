# Native OW heterogeneous integer/string dictionaries

This experimental LLVM milestone adds a new `mixed_map` type, while
preserving the separate and existing `int_map` and `string_map` ABIs.

```ow
fn make():
  returns: mixed_map
  return: { name: "OW", version: 6 }

main:
  set config: make()
  print: config["name"]
  set config["version"]: "six"
  print: map_size(config)
```

## Tagging and ownership

`%ow.mixed = { i32 tag, i32 number, %ow.string text }` uses tag `0` for
an integer and tag `1` for an immutable UTF-8 string with reference counting.
A `mixed_map` stores `%ow.mixed` values in string-keyed heap entries.
Map nodes own both keys and values.

- A heterogeneous map literal with at least one integer and one string
  automatically uses `mixed_map`. Homogeneous maps remain specialized.
- Mixed-map values can be updated from integer to string and vice versa.
- `map_set`, `map_get` (lazy fallback), `map_delete`, `map_has`,
  `map_size`, indexed reads/writes and aliases are supported.
- Indexing or returning a mixed value transfers a managed tagged value.
  The string portion, when present, survives destruction of a temporary map.
- Printing tagged values dispatches at runtime to the integer or UTF-8 path.
  `returns: mixed_map` and `returns: mixed` enable native function ABI.
- Dynamic strings have reference-counted ownership and are released on
  replacement, deletion, function/local exit or disposal.

## Current limitations

This milestone does **not** unify the physical native representations of
`int_map`, `string_map`, and `mixed_map`; old ABIs remain intact.
Homogeneous maps cannot be promoted in place to mixed maps through mutation.
To store both kinds, initialize with a heterogeneous literal. Empty `{}`
still creates an integer-valued map.

The mixed value supports only integers and strings (not `null`, `bool` as
a distinct type, floating-point values, arrays, nested maps, or objects).
`print` can format a mixed value, but arithmetic or `len` on a mixed value
requires explicit unboxing, which is not implemented here. Dictionary lookup
still uses a linked list (O(n)); a hash table is the next independent milestone.
The interpreter accepts more types and operations than LLVM.

The native reference counts are single-threaded, missing-key indexing aborts
rather than producing a catchable OW exception, and cyclic garbage collection
is not available. The native ABI is experimental.

## Verification

`examples/native_mixed_dictionaries.ow` is compiled to LLVM IR/Clang and
executed with ASan/LSan; GitHub CI compares native stdout byte-for-byte to
`ow run`. `examples/native_mixed_dictionaries_missing.ow` verifies that an
out-of-range lookup safely terminates with nonzero status.
