# Error handling: `try` / `catch`

Recover from runtime errors instead of aborting the program — a missing
file, a missing audio/mic device, a bad `int("abc")`, an out-of-range
operation, etc.

## Syntax

```
try:
  <statements>
catch <name>:
  <statements>
```

If any statement in the `try` block raises a runtime error, execution
jumps to the `catch` block with the error **message** bound to `<name>`
(a string). If the `try` block finishes with no error, the `catch` block
is skipped.

## Example

```ow
# Parse an int, falling back to a default on bad input.
fn parse_or(s, fallback):
  try:
    return: int(s)
  catch e:
    return: fallback

main:
  print: parse_or("42", 0 - 1)    # 42
  print: parse_or("abc", 0 - 1)   # -1

  try:
    call: audio_play("missing.wav")
  catch e:
    print: "no sound: " + e        # recovers, keeps running
  print: "still running"
```

## Semantics & notes

- The bound name holds the error message string (empty string if the
  error had no message). It lives in the current scope.
- `return` inside a `try` works normally — it returns from the enclosing
  function and is **not** intercepted by `catch`.
- `assert(...)` failures are a hard stop (they exit the process) and are
  **not** catchable — that is intentional, so tests fail loudly.
- There is no `finally` clause (yet).

## Implementation notes

- `try`/`catch` are parsed as indented blocks (same shape as `if`/`else`):
  `Lexer` needs no change, `Parser.parseTry` builds an `Ast.TryStmt`, and
  the interpreter runs the `try` block inside a Java `try/catch`.
- The interpreter catches `RuntimeException` but **re-throws** the
  internal `ReturnSignal` / `ResponseSignal` control-flow exceptions, so
  `return`/`respond` inside a `try` behave correctly.
- Verified: bad `int` and a missing audio file are caught and recovered;
  a `return` inside `try` still returns; an error-free `try` skips its
  `catch`.
