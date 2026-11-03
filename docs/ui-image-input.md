# UI widgets: `ui_image` and `ui_input`

Two new widgets for the Ow UI tree, so UIs can show pictures (sprites,
logos, themes) and read typed text. They render in **both** backends —
the web page (`ui_run` / `ui_run_web`) and the native window
(`ui_run_native`) — like the other `ui_*` widgets.

| Builtin | Args | Description |
|---------|------|-------------|
| `ui_image(path)` | 1 (string) | Show the image at `path` (PNG/JPG/GIF/WEBP/SVG). Resolved against the working directory. |
| `ui_input(value, handler)` | 2 (string, string) | A text field pre-filled with `value`; when the user presses **Enter**, calls the Ow function named `handler` with the current text. |

## Example

```ow
class App:
  field msg
  field word
  fn new():
    set this.msg: "type a word and press Enter"
    set this.word: ""
  fn on_word(w):
    set this.word: w
    set this.msg: "you typed: " + w
  fn view():
    return: ui_col([
      ui_heading("Image + Input"),
      ui_image("assets/logo.png"),
      ui_text(this.msg),
      ui_input(this.word, "app.on_word")
    ])

main:
  set app: new App()
  call: ui_run("app.view", "Demo")
```

## Notes

- `ui_input`'s handler receives the text as a **string**; parse it with
  `int(...)` if you need a number.
- Submission is on **Enter**. The field is not re-rendered while typing,
  so focus/caret are preserved; after a submit the tree re-renders with
  whatever the handler set.
- `ui_image` paths are resolved against the process working directory
  (same as the audio/persistence builtins), so run from the project root.

## Implementation notes

- `Interpreter.java`: `ui_image` → `{type:"image", src}`; `ui_input` →
  `{type:"input", value, onInput}`.
- **Web** (`UiRuntime`): a new `/asset?path=...` endpoint serves the image
  bytes (content-type by extension); the page renders `<img>` and a text
  `<input>` that POSTs `/event?id=..&value=<text>` on Enter, and the
  server passes that value to the handler.
- **Native** (`SwingUiRuntime`): `ui_image` loads via `ImageIO` into a
  `JLabel` (falls back to a `[image: path]` label if unreadable);
  `ui_input` is a `JTextField` that fires its handler on Enter.
- Verified: the **web** backend end-to-end over HTTP — `/asset` returns
  the PNG (200, `image/png`); the state tree carries the image/input
  nodes; posting a value runs the handler and the tree updates
  (`you typed: apple`). The **native** backend is compile-verified only
  (no headless display available to open a real window).
