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

### Important: this is loudness, not speech recognition

`mic_level` reports **how loud** the microphone input is, not **what was
said**. It is enough for "speak/clap to continue" style mechanics, but it
does **not** turn audio into words.

Real speech-to-text (e.g. for a read-aloud reading game) needs a
transcription engine. The JDK has none, so that would be a separate,
larger change: add an offline engine such as [Vosk](https://alphacephei.com/vosk/)
(Java bindings + a language model) behind a new builtin like
`mic_transcribe(ms)`. That introduces a dependency and a bundled model,
so it is intentionally **out of scope** for this change.

## Implementation notes

- All four builtins live in `Interpreter.java` (registered in `BUILTINS`,
  dispatched in `evalBuiltin`).
- `audio_music` keeps a single `Clip` reference so `audio_stop` can stop it;
  one-shots from `audio_play` attach a `LineListener` that closes the clip
  on `STOP` to avoid leaking lines.
- `mic_level` opens a 16 kHz / 16-bit / mono `TargetDataLine`, reads for the
  requested duration, and returns `peak / 32767 * 100`. It throws a clear
  error when no microphone line is available.
- Verified: `mvn compile` (BUILD SUCCESS) and runtime `ow run` of
  `audio_play` / `audio_music` / `audio_stop` on a real audio device.
  `mic_level` is compile-verified; it was not auto-run (it would open the
  live microphone).
