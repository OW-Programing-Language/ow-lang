# Audio & microphone builtins

Four new built-in functions, implemented with the pure-JDK
`javax.sound.sampled` API (no external dependencies added to `pom.xml`).

## Audio playback

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `audio_play(path)`  | 1 (string) | `0` | Play a sound **once**, fire-and-forget. The clip frees itself when it finishes. |
| `audio_music(path)` | 1 (string) | `0` | Play `path` as **looping** background music, replacing any track started by a previous `audio_music`. |
| `audio_stop()`      | 0 | `0` | Stop the background music started by `audio_music`. |

- `path` may be absolute or relative to the process working directory.
- Supported formats are those the JDK decodes natively: **WAV**, **AIFF**,
  **AU**. (MP3/OGG would require an extra decoder dependency — not added.)

```ow
main:
  call: audio_music("assets/theme.wav")   # background loop
  call: audio_play("assets/click.wav")    # one-shot
  # ... later ...
  call: audio_stop()
```

## Microphone

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `mic_level(ms)` | 1 (int) | `0`–`100` | Record from the default microphone for `ms` milliseconds and return the **peak loudness** as an integer 0–100. |

```ow
# "Speak to continue" — wait until the player makes noise.
main:
  print: "Say something!"
  set loud int: 0
  while: loud < 20
    set loud: mic_level(300)
  print: "Heard you!"
```

### `mic_level` is loudness, not words

`mic_level` reports **how loud** the microphone input is, not **what was
said**. For turning speech into words, use `mic_transcribe` below.

## Speech-to-text

| Builtin | Args | Returns | Description |
|---------|------|---------|-------------|
| `mic_transcribe(model_path, ms)` | 2 (string, int) | string | Record from the default microphone for `ms` ms and return the recognized speech as **lowercased text** (`""` if nothing is recognized). |

Powered by the offline [Vosk](https://alphacephei.com/vosk/) engine (added
as a Maven dependency — it bundles the native libraries). Everything runs
locally; no audio leaves the machine.

```ow
# Read-aloud: pass if the player says the target word.
main:
  print: "Say the word: APPLE"
  set heard: mic_transcribe("models/vosk-en", 3000)
  if: heard == "apple"
    print: "Correct!"
  else:
    print: "I heard: " + heard
```

### You must provide a model

The Vosk **engine** ships with this change, but language **models** do
not — they are large (a small English model is ~40 MB) and must not live
in the repo. Download one from
<https://alphacephei.com/vosk/models>, unzip it, and pass the folder as
`model_path` (absolute, or relative to the working directory). The model
is loaded once and cached per path.

### Packaging

Adding Vosk means the runtime classpath needs the `vosk` + `jna` jars
(and Vosk's bundled native libraries). This is handled by building
`OwIDE.jar` as an **uber-jar** via the `maven-shade-plugin`: all
dependencies — including `win32-x86-64/libvosk.dll`, `libvosk.so`,
`libvosk.dylib` and JNA's `jnidispatch` natives — are shaded in.

Because `ow package` bundles the single `OwIDE.jar` into its jpackage
app-image (see `Packager.java`), the packaged `.exe`/installer gets
Vosk automatically with no change to the packager. `java -jar
OwIDE.jar` and `ow run` likewise have it on the classpath.

> Still external, by design: the Vosk **model** (see above) — it is not
> shaded into the jar and must be shipped/downloaded alongside the app.

## Implementation notes

- All four builtins live in `Interpreter.java` (registered in `BUILTINS`,
  dispatched in `evalBuiltin`).
- `audio_music` keeps a single `Clip` reference so `audio_stop` can stop it;
  one-shots from `audio_play` attach a `LineListener` that closes the clip
  on `STOP` to avoid leaking lines.
- `mic_level` opens a 16 kHz / 16-bit / mono `TargetDataLine`, reads for the
  requested duration, and returns `peak / 32767 * 100`. It throws a clear
  error when no microphone line is available.
- `mic_transcribe` feeds the same 16 kHz mono capture into a Vosk
  `Recognizer`, then parses the engine's `{"text": "..."}` result with the
  built-in `Json` reader. Models are cached per path in `voskModels`.
- Verified: `mvn compile` (BUILD SUCCESS, including the Vosk dependency) and
  runtime `ow run` of `audio_play` / `audio_music` / `audio_stop` on a real
  audio device. `mic_level` and `mic_transcribe` are compile-verified only;
  they were not auto-run (they open the live microphone, and `mic_transcribe`
  also needs a downloaded model).
