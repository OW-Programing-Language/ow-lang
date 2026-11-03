# Math builtins

Integer math helpers, so programs don't have to hand-roll them.
(Ow numbers are integers; see `docs/ROADMAP.md` #6 for floats.)

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `abs(n)` | 1 | int | Absolute value. |
| `min(a, b)` | 2 | int | Smaller of two. |
| `max(a, b)` | 2 | int | Larger of two. |
| `pow(base, exp)` | 2 | int | `base` raised to `exp` (`exp >= 0`). |
| `sqrt(n)` | 1 | int | Integer **floor** square root (`n >= 0`). |
| `clamp(n, lo, hi)` | 3 | int | `n` constrained to `[lo, hi]` (`lo <= hi`). |

`mod(a, b)` already exists.

Errors (thrown as runtime errors): `pow` with `exp < 0`, `sqrt` with
`n < 0`, `clamp` with `lo > hi`.

## Example

```ow
main:
  print: pow(3, 4)         # 81
  print: sqrt(26)          # 5  (floor)
  print: clamp(99, 0, 10)  # 10
  print: abs(0 - 7)        # 7  (negatives are written 0 - n)
```

## Implementation notes

- In `Interpreter.java`: `abs`/`min`/`max` inline in `evalBuiltin`;
  `pow`/`sqrt`/`clamp` as helper methods. Pure JDK integer arithmetic,
  no dependencies.
- `sqrt` returns the largest `r` with `r*r <= n`.
- Verified via a program covering each builtin and its edge cases
  (`pow(x,0)`, perfect vs non-perfect `sqrt`, clamp below/in/above).
