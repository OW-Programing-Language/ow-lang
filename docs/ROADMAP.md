# Claude Code task: grow the Ow language toward game/app parity

You are working in **`ow-lang`** (the Ow programming language: a Java/JVM
interpreter + LLVM backend + OwIDE). Your job is to add the missing
language features below, one clean PR per feature, each **built and
verified** before you open it.

## Repo facts (read first)

- Maven project, `groupId dev.oreo`, main class `dev.oreo.Main`. Java 17
  source target; a JDK 17+ and Maven are installed.
- Build: `mvn -q package -DskipTests` → produces `target/OwIDE.jar`
  (an **uber-jar**; dependencies are shaded in via `maven-shade-plugin`).
- Run a program: `java -jar target/OwIDE.jar run path/to/file.ow`
  (equivalently `ow run file.ow`).
- The interpreter lives in `src/main/java/dev/oreo/Interpreter.java`.
  Builtins are registered in **two** places:
  1. the `BUILTINS` set (names), and
  2. the `evalBuiltin(...)` `switch` (dispatch to a helper method).
  Add new builtins by extending both, plus a private helper method.
  Follow the existing `builtinRand` / `builtinAudioPlay` helpers for style
  (`requireArgCount`, `str(call,i,env)`, `toInt(evalExpr(...))`,
  `assign`/`resolve` for scope).
- Language style: object-oriented, **one class per file**, logic in
  classes, I/O in `main`; see the example repos `tictactoe`, `connect4`,
  `guess-the-number` in the same GitHub org. Numbers are **integers only**
  (see `Json.java`); strings concatenate with `+`.

## Already done — do NOT re-add

- Audio: `audio_play`, `audio_music`, `audio_stop` (pure JDK
  `javax.sound.sampled`).
- Microphone: `mic_level(ms)` (loudness) and `mic_transcribe(model, ms)`
  (offline Vosk STT; Vosk is a shaded dependency).
- A **function-scope fix** in `assign()` (locals no longer clobber
  globals). Keep it.
- Existing builtins: `input, int, len, array, str, mod, rand, time_ms,
  json, json_parse, param, query, body, body_json, response, ui_text,
  ui_heading, ui_button, ui_row, ui_col, ui_grid, ui_disc, ui_run,
  ui_run_web, ui_run_native, ui_stop`.

## Git & repositories (where things go)

- GitHub org: **`OW-Programing-Language`**. Use the `gh` CLI; you are
  authenticated as an org admin.
- **Language features from this document go in the `ow-lang` repo.** Clone
  it fresh if needed (`gh repo clone OW-Programing-Language/ow-lang`); its
  remote `origin` must point at `ow-lang` (an older checkout may still
  point at the dead `OW` repo — fix with
  `git remote set-url origin https://github.com/OW-Programing-Language/ow-lang.git`).
- **Do NOT put language changes in the game repos** (`codewash-rpg`,
  `tictactoe`, `connect4`, `guess-the-number`, `night`, `owdocs`). Those
  are separate; only touch them if a feature needs a matching example and
  the task says so (then open a separate PR in that repo).
- For each feature: branch off the latest `main`
  (`git checkout main && git pull`, then `git checkout -b feat/<name>`),
  commit, **push to `origin` on `ow-lang`**
  (`git push -u origin feat/<name>`), and open a PR with `gh pr create`
  against `ow-lang` `main`. Merge only when it builds and is verified
  (`gh pr merge --merge --delete-branch`); open as **draft** if it can't
  be runtime-verified here.
- **Never commit build artifacts or large assets**: no `target/`, no
  `*.jar`, no Vosk models, no generated audio. Check `git status` before
  committing; respect `.gitignore`.
- End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
  and every PR body with the Claude Code generation line.

## Ground rules (every feature)

1. **Verify before PR.** `mvn -q package -DskipTests` must succeed, and
   you must run at least one `.ow` program through `target/OwIDE.jar`
   exercising the feature. Paste the program + its output in the PR.
2. **Prefer pure JDK.** Only add a Maven dependency when the JDK truly
   can't do it; if you do, keep the uber-jar working (shade must bundle
   it) and note any native-lib/model caveats.
3. **One feature = one branch = one PR.** Small, reviewable commits.
   If a feature can't be runtime-verified in a headless/CI box (needs a
   device/model), open it as a **draft** and say why.
4. **Scout rule.** Add/extend docs under `docs/` for each feature (a
   short table: builtin, args, returns, example). Update any affected
   docs. Don't leave stale notes.
5. **Don't break existing programs.** The example games and the web
   framework must still run. Numbers stay integers unless you implement
   floats as an explicit, documented feature (#6).
6. **Confirm each item isn't already present** before implementing it
   (grep `BUILTINS`, the lexer keywords, the parser).

## Features to add — in priority order

Each item: **goal**, a **proposed surface** (names are suggestions; keep
them consistent with existing builtins), **hints**, and **done-when**.

### 1. File persistence (save/load) — HIGH
- Goal: standalone programs (e.g. game saves, profiles, high scores,
  achievements) can persist data between runs without the web stack.
- Surface: `save(path, value)` writes a value as JSON; `load(path)`
  returns the parsed value (or `0`/null if missing); `exists(path)`
  returns 1/0. Reuse `Json.write`/`Json.parse` already in the repo.
- Hints: `java.nio.file.Files`; resolve relative paths against the
  working dir (see `mediaFile(...)`); create parent dirs on save.
- Done-when: a program saves a record, a second run loads it back.

### 2. String builtins — HIGH
- Goal: text skills (reading/writing/spelling), parsing, formatting.
- Surface: `upper(s)`, `lower(s)`, `trim(s)`, `substr(s, start, len)`,
  `index_of(s, sub)` (−1 if absent), `contains(s, sub)` (1/0),
  `replace(s, a, b)`, `split(s, sep)` → array, `char_at(s, i)`.
- Hints: trivial `java.lang.String` wrappers; mind integer return types.
- Done-when: a program uppercases, splits, and searches a string.

### 3. Math builtins — HIGH
- Goal: remove hand-rolled `ipow`/`idiv`/`imod` helpers; enable scaling.
- Surface: `abs(n)`, `min(a,b)`, `max(a,b)`, `pow(base, exp)`,
  `sqrt(n)` (integer floor root), `clamp(n, lo, hi)`. `mod` exists.
- Hints: integer-only semantics; document rounding (floor).
- Done-when: `pow(3,4)==81`, `sqrt(26)==5`, `clamp` behaves.

### 4. `sleep(ms)` and a real clock — MEDIUM
- Goal: animation/pacing, timed prompts. `time_ms` exists but wraps.
- Surface: `sleep(ms)` (blocks), and consider `now_ms()` returning full
  epoch millis (as integer) without the current modulo truncation.
- Hints: `Thread.sleep`. Keep `time_ms` for back-compat.
- Done-when: a loop visibly paces itself.

### 5. Error handling (`try` / `catch`) — MEDIUM
- Goal: programs can recover from a missing audio device, missing file,
  failed `int("abc")`, etc., instead of aborting. Today there is none.
- Surface: `try: <block> catch e: <block>` (bind the message to `e`),
  OR a builtin `ok(fn)` pattern if adding syntax is too invasive.
- Hints: this touches the **Lexer/Parser/Ast**, not just builtins. Wrap
  interpreter `RuntimeException`s into a catchable Ow value.
- Done-when: a program survives `audio_play("missing.wav")`.

### 6. Decimal/float numbers — MEDIUM (design change)
- Goal: real division and growth curves (the original game used
  `1.035^level`); currently everything is integer.
- Surface: a `float`/`num` type, float literals (`1.035`), and `/`
  producing a float; `floor`/`round`/`ceil` to convert back.
- Hints: large change — lexer (number literals), interpreter arithmetic,
  `Json`, `formatValue`, `str`. Decide coercion rules and document them.
  This is the riskiest item; keep integer programs working unchanged.
- Done-when: `1.035 * 2`, `7 / 2 == 3.5`, and `floor(3.5)==3` work.

### 7. UI: image + text-input widgets — MEDIUM
- Goal: graphical games/skills (sprites, themes) and typed answers in the
  windowed UI. Today `ui_*` has no image and no text field.
- Surface: `ui_image(path)` (renders a PNG/JPG), `ui_input(handler)` or
  `ui_field(value, handler)` (a text box that reports its value).
- Hints: extend `UiRuntime` / `SwingUiRuntime` / `WebRuntime` and the
  `uiNode` data shape. Images: load via `ImageIO` (native) and `<img>`
  (web). Keep the shared widget-tree model.
- Done-when: a window shows an image and reads typed text back into Ow.

### 8. Audio polish — LOW
- Goal: volume control and overlapping channels; optionally MP3/OGG.
- Surface: `audio_volume(0..100)`, allow N concurrent `audio_play`.
  MP3/OGG would need a decoder dependency — only if justified.
- Done-when: volume changes audibly; two sounds overlap.

### 9. Test/assert support — LOW (enables CI quality gates)
- Goal: let Ow programs assert, so the language (and games) can have a
  test suite and CI. Surface: `assert(cond, message)` that exits non-zero
  on failure; optionally an `ow test <dir>` runner.
- Done-when: a failing assert exits non-zero with a clear message.

## Suggested order of PRs

`#1 save/load` → `#2 strings` → `#3 math` → `#4 sleep` → `#9 assert`
(these are small, pure-JDK, high-leverage) → then `#5 try/catch` and
`#7 UI image/input` (parser/runtime work) → `#6 floats` last (biggest
blast radius).

## Deliverables

For each feature: the interpreter/parser changes, a `docs/<feature>.md`
entry, a tiny example `.ow` demonstrating it, a verified build, and a PR
with the program + output pasted in. Group nothing; keep PRs independent
so they can merge in any order (except floats, which may rebase on the
others).

---
*Context: these close the remaining gaps between the Ow port of the
"Code Wash" educational RPG (`codewash-rpg`) and the original
JavaScript/React game — persistence (profiles/achievements/saves), text
skills, graphical skills/themes, and smoother pacing/robustness.*
