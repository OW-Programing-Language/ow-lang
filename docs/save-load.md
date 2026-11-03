# File persistence: `save` / `load` / `exists`

Store and retrieve Ow values on disk as JSON, with no web stack. Useful
for game saves, player profiles, high scores and achievements.

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `save(path, value)` | 2 (string, any) | `0` | Write `value` to `path` as JSON. Parent directories are created automatically. |
| `load(path)` | 1 (string) | any | Read and parse the JSON at `path`. Returns `0` if the file does not exist. |
| `exists(path)` | 1 (string) | `0` / `1` | `1` if the file exists, else `0`. |

- `path` may be absolute or relative to the process working directory
  (same resolution as the audio builtins).
- Values round-trip through the built-in `Json` reader/writer, so any
  value `json(...)` can represent works: integers, strings, booleans,
  arrays, and records/maps. (Ow has no floats.)
- Use `exists(path)` to tell "missing file" apart from a value that was
  genuinely saved as `0`.

## Example

```ow
# First run writes a profile; later runs load it back.
main:
  if: exists("save/profile.json") == 1
    set p: load("save/profile.json")
    print: "Welcome back, " + p[0] + " (level " + p[1] + ")"
  else:
    set p: ["Alex", 1, 0]
    call: save("save/profile.json", p)
    print: "New profile created for " + p[0]
```

Running it twice prints "New profile created..." then
"Welcome back, Alex (level 1)".

## Implementation notes

- Implemented in `Interpreter.java` (`builtinSave` / `builtinLoad` /
  `builtinExists`), pure JDK via `java.nio.file.Files` and the existing
  `Json` reader/writer. No new dependencies.
- `save` creates parent directories with `Files.createDirectories`.
- Verified: a value saved in one `ow run` process is loaded back intact
  in a separate process; `exists` reports 0 before and 1 after; `load`
  of a missing path returns `0`.
