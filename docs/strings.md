# String builtins

Text helpers for parsing, formatting and the text skills
(reading / writing / spelling).

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `upper(s)` | 1 | string | Uppercase copy. |
| `lower(s)` | 1 | string | Lowercase copy. |
| `trim(s)` | 1 | string | Copy with leading/trailing whitespace removed. |
| `substr(s, start, len)` | 3 | string | Up to `len` chars from `start`. Bounds are clamped (never errors). |
| `index_of(s, sub)` | 2 | int | Index of the first `sub`, or `-1` if absent. |
| `contains(s, sub)` | 2 | `0`/`1` | `1` if `s` contains `sub`. |
| `replace(s, a, b)` | 3 | string | Replace every literal `a` with `b`. |
| `split(s, sep)` | 2 | array | Split on literal `sep` into an array of strings. |
| `char_at(s, i)` | 2 | string | The character at `i` as a 1-char string, `""` if out of range. |

Notes:
- `len(s)` (existing) returns a string's length.
- `substr` clamps `start`/`len` to the string, so it never throws on
  out-of-range values — handy for defensive parsing.
- `split` keeps empty fields; an **empty** separator splits `s` into its
  individual characters.

## Example

```ow
main:
  set name: trim("  code wash  ")
  print: upper(name)                 # CODE WASH
  set parts: split("cat,dog,fish", ",")
  print: parts[0] + "/" + parts[2]   # cat/fish
  if: contains(lower("APPLE"), "ppl") == 1
    print: "match"
```

## Implementation notes

- Implemented in `Interpreter.java`, thin wrappers over `java.lang.String`
  (`upper`/`lower`/`trim`/`index_of`/`contains`/`replace` inline in
  `evalBuiltin`; `substr`/`split`/`char_at` as helper methods with clamped
  bounds). Pure JDK, no dependencies.
- Verified via a program exercising every builtin, including clamping and
  out-of-range cases.
