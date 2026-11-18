# Native dictionary enumeration with insertion order

OW's experimental LLVM backend supports `map_keys(map)` and
`map_values(map)`, matching the interpreter's **insertion-order semantics**.

```ow
main:
  set scores: { first: 10, second: 20, third: 30 }
  set names: map_keys(scores)
  set values: map_values(scores)
  print: names[0]
  print: values[2]
```

## Snapshot representations

| Input native map | `map_keys` result | `map_values` result |
|---|---|---|
| `int_map` | `string_array` | `array` (i32) |
| `string_map` | `string_array` | `string_array` |
| `mixed_map` | `string_array` | `mixed_array` |

`mixed_array` is a new native ARC-managed array of tagged `mixed` values,
supporting `len`, indexed reads, aliases and typed function boundaries.
It can retain nested dictionary values, allowing `values[i]["child"]` style
reads. Indexing returns an owned tagged copy and checks bounds before access.

Every enumeration returns a **snapshot**. Deleting/replacing the source map
does not remove its previously returned keys or values; string and nested
map references are individually retained for the snapshot and released when
its final array owner exits.

## Stable insertion order and hash-table performance

The resizing hash table from PR #58 is retained for expected average O(1)
lookup/insertion/deletion. Each node now also participates in an independent
doubly linked insertion-order chain.

- Inserting a new key appends the node at the end of the order chain.
- Updating an existing key does **not** change its original position.
- Deletion removes the node from both the bucket chain and order chain.
- Deleting then reinserting a key places it at the **end**.
- Rehashing relinks bucket pointers without disturbing insertion order.
- `map_keys` and `map_values` walk the order chain in **O(n)** time and
  allocate an owned result array proportional to map size.
- Empty maps produce empty arrays.

The map-header and entry layouts have grown to hold insertion-order pointers,
and the new `mixed_array` representation is experimental. **Recompile
previously generated native binaries** after this ABI change.

## Current limits

This milestone exposes snapshots of the existing native value types:
integer, string, and tagged int/string/nested-map values. It does not yet
support generic mixed-array construction, mutation, collection literals of
arbitrary elements, native `map_keys` directly on a dynamically tagged
`mixed` map, or JSON. OW's interpreter remains the complete feature
reference for objects and collections.

String reference counters remain non-atomic. For nested map values, runtime
cycle guards from PR #59 remain in place.

## Verification

The GitHub Actions workflow compiles `examples/native_map_iteration.ow` to
LLVM/Clang, compares its output to `ow run` and to the expected insertion
order, and runs under AddressSanitizer/LeakSanitizer. It covers >40 key
insertions (resizing), updates, deletion/reinsertion, Unicode, owned snapshots,
nested values, function boundaries, empty maps, and out-of-bounds checks.
