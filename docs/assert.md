# `assert` — test & sanity checks

Lets Ow programs check conditions and fail loudly, so the language and
its examples can have tests and a CI quality gate.

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `assert(cond)` | 1 | `0` | Pass if `cond` is truthy; otherwise fail. |
| `assert(cond, message)` | 2 | `0` | Same, with a message shown on failure. |

- Truthiness matches `if` / `while` (e.g. `0` and `false` fail; non-zero
  and `true` pass).
- On failure, prints `Assertion failed[: message]` to **stderr** and
  exits with a **non-zero** status. On success it returns `0` and
  execution continues.

## Example

```ow
# tests/math_test.ow
main:
  call: assert(pow(3, 4) == 81, "pow(3,4) should be 81")
  call: assert(sqrt(26) == 5, "floor sqrt of 26 is 5")
  call: assert(len(split("a,b,c", ",")) == 3)
  print: "math tests passed"
```

Run it as part of CI:

```bash
ow run tests/math_test.ow   # exit code 0 = pass, non-zero = failure
```

A failing assert looks like:

```
Assertion failed: pow(3,4) should be 81
```

and the process exits `1`, so a shell/CI step fails.

## Implementation notes

- `Interpreter.java` → `builtinAssert`, reusing the interpreter's
  `truthy(...)` so `assert` agrees with `if`/`while`. Pure JDK.
- Verified: a passing program exits `0`; a failing `assert` prints the
  message to stderr, stops execution, and exits `1`.

### Possible follow-up
An `ow test <dir>` runner that executes every `*_test.ow` and reports a
pass/fail summary (roadmap #9, optional) would build on this.
