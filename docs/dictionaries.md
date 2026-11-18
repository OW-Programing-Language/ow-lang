# Dictionaries (maps)

A dictionary maps **string keys** to values with average‑case O(1) lookup,
insertion and deletion. Keys may contain spaces, punctuation and any character,
and entries keep their **insertion order**.

## Literals

```ow
set sprites: {
  "Blaze": "hostile/blaze.gif",
  "Magma Cube": "hostile/magmacube.png",
  "Zombie": "hostile/zombie.png"
}
```

- Keys are quoted strings (for spaces/punctuation) **or** bare identifiers.
- Values may be any OW value: numbers, strings, booleans, arrays, objects, or
  other dictionaries.
- `{}` is an empty dictionary. Literals may span multiple lines.

### Dictionaries vs. objects

They share one representation (an insertion‑ordered string→value map), so the
difference is only in **how you spell the key**:

| | Keys | Access |
|---|---|---|
| Object style | bare identifiers: `{ name: "Steve" }` | `o.name` or `o["name"]` |
| Dictionary style | quoted strings: `{ "Magma Cube": 1 }` | `d["Magma Cube"]` |

`.field` access only works for identifier‑shaped keys; `["..."]` works for any
key. A value built either way is the same thing and round‑trips through JSON
identically.

## Indexing

Read and write with a literal or a computed key:

```ow
set name: "Blaze"
set path: sprites[name]          # read (variable key)
set sprites["Zombie"]: "z.png"   # write / insert
set mob: "Creeper"
set sprites[mob]: "c.png"        # dynamic write
```

- Assigning a **new** key inserts it; assigning an existing key updates it.
- Reading a **missing** key raises an error — use `map_get` for a default.
- Nested access chains: `cfg["mobs"]["Blaze"]["hp"]`.
- Keys must be strings; a non‑string key (e.g. `d[5]`) is rejected.

## Default‑value lookup

```ow
set path: map_get(sprites, name, "hostile/zombie.png")
```

`map_get(map, key, fallback)` returns the stored value when the key exists,
otherwise the `fallback`. The fallback applies **only when the key is absent**,
so stored falsy values are preserved:

```ow
set v: { "zero": 0, "disabled": false, "empty": "", "nothing": null }
map_get(v, "zero", 100)       # 0
map_get(v, "disabled", true)  # false
map_get(v, "empty", "x")      # ""
map_get(v, "nothing", 42)     # null
map_get(v, "missing", 42)     # 42
```

The fallback is **evaluated lazily** — only when the key is missing — so
`map_get(m, "a", expensive())` never runs `expensive()` when `a` is present.

## Operations

| Builtin | Result |
|---|---|
| `map_has(m, key)` | `true`/`false` — key existence |
| `map_get(m, key, fallback)` | value if present, else `fallback` (lazy) |
| `map_set(m, key, value)` | insert/update; returns the map |
| `map_delete(m, key)` | remove a key; returns the map |
| `map_keys(m)` | array of keys, in insertion order |
| `map_values(m)` | array of values, in insertion order |
| `map_size(m)` | number of entries |

`map_set`/`map_delete` mutate the dictionary in place and also return it, so they
compose. `map_has` distinguishes a stored `null` from an absent key.

## Iteration

Iterate with the existing `while` loop over `map_keys`, which enumerates keys in
**insertion order**:

```ow
set keys: map_keys(sprites)
set i: 0
while: i < len(keys)
  print: keys[i] + " -> " + sprites[keys[i]]
  set i: i + 1
```

## JSON

Dictionaries serialize to and parse from JSON objects, preserving keys exactly
(including spaces and case):

```ow
set text: json(sprites)          # {"Blaze":"hostile/blaze.gif", ...}
set back: json_parse(text)       # dictionary again
```

## Performance

Backed by a hash map: lookup, insertion and deletion are average‑case O(1).
`map_get` does a single keyed lookup, never a full scan or JSON conversion.

## Replacing a switch with a lookup

```ow
# Before: one switch arm per mob.
# After: one dictionary + one lookup.
fn mob_path(sprites, name):
  return: map_get(sprites, name, "hostile/zombie.png")
```

See `examples/mob_sprites.ow` for the full MobArt example.

## Backend support

The **interpreter** supports all dictionary functionality above, including
heterogeneous values, nested dictionaries, insertion-order iteration and JSON.
The experimental native **LLVM** backend supports **string-keyed
integer, string and mixed integer/string values**, checked dynamic indexing,
mutation and `map_size`, `map_has`, `map_get` (lazy fallback), `map_set`
and `map_delete`. Mixed maps use tagged values with managed UTF-8 strings.
This implementation uses a shared reference-counted, resizing UTF-8
[hash-table backend](native-hash-dictionaries.md) with average O(1) lookup,
insertion and deletion. `map_keys`, `map_values`,
nested object values and JSON remain interpreter-only. See
[native mixed dictionaries](native-mixed-dictionaries.md),
[native integer-valued dictionaries](native-int-dictionaries.md) and
[native string-valued dictionaries](native-string-dictionaries.md).
