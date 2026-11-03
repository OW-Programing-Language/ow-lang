# Decimal (float) numbers

Ow now has floating-point numbers alongside integers, for real division
and growth curves (e.g. `1.035 ^ level`).

## Literals & arithmetic

- A number with a decimal point is a **float**: `1.035`, `3.5`, `0.25`.
  (Negatives are still written `0 - 1.5`.)
- Arithmetic is **float if either operand is a float**, otherwise integer:

  ```ow
  7 / 2       # 3     (both ints -> integer division, unchanged)
  7.0 / 2     # 3.5   (a float operand -> real division)
  1.5 + 2     # 3.5   (mixed -> float)
  2.0 * 3     # 6     (value is 6, held as a float)
  ```

- Comparisons work across int/float: `3 == 3.0` is true, `3.5 < 4`.

## Converting back to int

| Builtin | Returns | |
|---|---|---|
| `floor(x)` | int | round toward −∞ |
| `ceil(x)` | int | round toward +∞ |
| `round(x)` | int | nearest (ties up) |
| `int(x)` | int | truncate toward zero (also parses `"3.9"` → 3) |

```ow
floor(3.7)   # 3
ceil(3.2)    # 4
round(3.5)   # 4
int(3.9)     # 3
```

## Back-compatibility (important)

Programs that never use a float literal behave **exactly as before** —
they only ever produce integers, and `/` between two integers is still
integer division. Floats are opt-in by writing a decimal point.

## Notes

- Printing drops a trailing `.0`: the float `3.0` prints as `3`, while
  `3.5` prints as `3.5`. The value is still a float.
- Floats are IEEE-754 doubles, so expect the usual precision artefacts
  (`1.035 * 1.035` prints `1.0712249999999999`). Use `round`/`floor` when
  you need a tidy integer.
- `save`/`load` preserve floats: a float is written with a decimal point
  and read back as a float (an integer stays an integer).
- Array indices and sizes still require integers — `floor`/`int` a float
  first if needed.

## Implementation notes

- `Lexer` emits a `FLOAT` token for a digit run followed by `.` and more
  digits; `Parser` builds `Ast.FloatLit`; the interpreter evaluates it to
  a Java `Double`.
- Binary arithmetic and comparison promote to `double` when either side
  is a `Double`; otherwise the original integer paths run unchanged.
- `Json` writes doubles with a decimal and parses decimals back to
  doubles, so persistence round-trips.
- Verified with an `assert` suite covering integer regressions
  (`7/2==3`, `pow`), float math, `floor`/`ceil`/`round`/`int`, a growth
  curve, and float save/load — plus a full run of the existing math game
  (no behaviour change).
