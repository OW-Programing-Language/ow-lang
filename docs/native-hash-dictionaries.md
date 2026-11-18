# Native OW hash dictionaries

The LLVM backend now uses a **shared, collision-safe hash-table runtime**
for the existing `int_map`, `string_map` and `mixed_map` native types.
This replaces the former per-map O(n) linked-list key lookup, without
changing OW's YAML-like source syntax.

```ow
main:
  set settings: { app: "OW", workers: 4 }
  print: settings["app"]
  set settings["workers"]: "auto"
  print: settings["workers"]
```

## Implementation

- Shared **FNV-1a 64-bit** UTF-8 hash, treating each byte as unsigned.
- Initially **16** zero-initialized buckets, with power-of-two capacity and
  a bitmask for selecting a bucket.
- Separate chaining handles colliding keys; full string equality uses
  `strcmp` rather than only matching hashes.
- **Automatic resizing** to twice as many buckets once the load factor
  exceeds **0.75** (13th, 25th, 49th insertion, etc.). Rehash relinks
  existing nodes without copying, retaining, or freeing their keys/values.
- `map_get`, indexed reads, `map_has`, `map_set`, and `map_delete`
  now use the bucket index. Average lookup, insertion and deletion are
  **O(1)** under a reasonable key distribution; worst case remains
  O(n) for adversarial collisions. Rehashing is O(n).
- Keys are managed UTF-8 strings. `string_map` values and mixed-map
  string variants retain the existing reference-counted ownership rules.
- The final map owner releases all nodes and keys/values by walking the
  buckets, then frees the bucket array and map header.
- `int_map`, `string_map` and `mixed_map` remain three distinct
  **experimental** native ABIs (now all using bucket-pointer headers).
  Rebuild generated LLVM binaries after this ABI change.

## Verification

`examples/native_hash_dictionaries.ow` exercises **64+ entries in every
map type**, triggering multiple table resizes, and uses four strings with
the same hash modulo 128 to verify collisions. The test checks lookup,
deletion, aliasing, self-assignment, dynamically allocated string values,
and lazy fallbacks. GitHub Actions compares native output to `ow run`
and to the expected output, and runs Clang's AddressSanitizer and
LeakSanitizer with leak detection enabled.

Missing keys continue to abort in native programs (exception lowering is
future work).

## Remaining limitations

The native maps currently support only UTF-8 string keys and integer/string
values. They are single-threaded (ARC counters are non-atomic), have no
general GC or null/object/nested-map values, and now support insertion-ordered native `map_keys` and `map_values`
through [ordered iteration](native-map-iteration.md), but not JSON lowering.
FNV-1a is not a
collision-resistant cryptographic hash: hostile user-controlled keys can
still cause worst-case performance. Production hardened hashing, benchmarks,
and automated adversarial collision tests are future work.
