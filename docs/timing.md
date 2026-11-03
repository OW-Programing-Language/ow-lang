# Timing builtins

For pacing, animation frames and timed prompts.

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `sleep(ms)` | 1 | `0` | Block for `ms` milliseconds (`ms <= 0` is a no-op). |
| `now_ms()` | 0 | int | Milliseconds **since the program started** (monotonic). |

## Why `now_ms` counts from start (not the epoch)

Ow numbers are 32-bit integers. Unix epoch milliseconds (~1.7e12) do not
fit in a 32-bit int, so `now_ms()` returns time **since the program
started** instead. That is exactly what you want for measuring durations
and pacing, and it stays correct for ~24 days of runtime.

The older `time_ms()` (wall-clock millis modulo 1e9) still exists for
back-compat, but `now_ms()` is preferred for timing because it never
jumps backwards at the modulo boundary.

## Example

```ow
# Simple frame pacing: ~10 fps.
main:
  set frame int: 0
  while: frame < 30
    print: "frame " + frame + " @ " + now_ms() + "ms"
    call: sleep(100)
    set frame: frame + 1
```

## Implementation notes

- `Interpreter.java`: `sleep` uses `Thread.sleep` (restores the interrupt
  flag and raises a clear error if interrupted); `now_ms` subtracts a
  `startMillis` captured at interpreter construction. Pure JDK.
- Verified: `now_ms()` advanced ~254 ms across `sleep(250)`.
