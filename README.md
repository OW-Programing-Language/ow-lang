# Ow

Ow is a small, indentation-based, **YAML-looking** programming language with a
Java-hosted toolchain. It ships with a tree-walking interpreter, an LLVM IR
backend (for compiling the procedural subset to native executables), a bundled
IDE (**OwIDE**), and a **modern web-based UI runtime** for building clickable GUIs.

```yaml
class Greeter:
  field name

  fn new(n):
    set this.name: n

  fn hello():
    return: "Hello, " + this.name + "!"

main:
  set g: new Greeter("world")
  print: g.hello()
```

## Language features

Ow is now a fully object-oriented language while keeping its clean,
indentation + `key: value` YAML aesthetic.

- **Variables & types** — `set x: 10`, optional typed form `set x int: 10`
  (`int`, `string`, `bool`, `array`, enum and class names are checked at runtime).
- **Control flow** — `if` / `else` / `else if`, `while`, and `switch` / `case` /
  `default` (a `case` may list several values: `case 2, 3:`).
- **Functions** — `fn name(params):`, `return:`, `call:`.
- **Enums** — `enum Color:` with indented variants, used as `Color.Red`.
- **Interfaces** — `interface Shape:` listing `fn` signatures.
- **Classes** — `field` and `fn` members, `new ClassName(args)`, a `new`
  constructor, and `this`.
- **Encapsulation** — `field private x` / `fn private helper()` (private members
  are rejected when accessed from outside the object).
- **Inheritance & polymorphism** — `class Loud(Named):` overrides methods; calls
  dispatch dynamically on the object's actual class. Interfaces give a shared type.
- **`to_string()`** — define it on a class and `print:` uses it automatically.
- **Arrays** — literals `[1, 2, 3]`, `array(n, fill)`, indexing, `len(...)`.
- **Records / maps** — literals `{name: "Ada", age: 36}`, field access `rec.name`.
- **Booleans** — `true` / `false`.
- **Builtins** — `input()`, `int()`, `str()`, `len()`, `array()`, `mod()`,
  `rand(n)`, `time_ms()`.

See [`examples/oop_demo.ow`](examples/oop_demo.ow) for a tour of all of the above.

## Built-in database (tables)

A `model` declaration **is a table** — a typed, auto-persisting collection of
records. No SQL, no driver, no setup: declaring a model gives you storage, and
rows are saved to `ow-data.json` automatically (override the path with the
`OW_DB_PATH` env var or `ow.db.path` system property).

```yaml
model Task:
  title string
  done bool
  priority int

main:
  save Task: {title: "Ship v1.0", done: true, priority: 1}
  save Task: {title: "Write docs", done: false, priority: 2}

  set open: find Task where done == false     # query, no SQL strings
  print: find Task                            # all rows (each gets an auto `id`)
  delete Task where done == true              # bulk delete
```

| Operation | Syntax |
|---|---|
| Insert | `save Model: {field: value, ...}` (auto-assigns `id`) |
| Query all | `find Model` → array of records |
| Query filtered | `find Model where <condition>` (fields used as bare names) |
| Delete | `delete Model` / `delete Model where <condition>` |

Records are plain Ow values, so `row.field` reads work and `len(find Model)`
counts rows. Data survives restarts — run a program twice and the table is
still there. See [`examples/db_demo.ow`](examples/db_demo.ow).

## Modern GUIs

Ow programs can open a **modern, clickable UI** and render it two ways from the
**same** widget tree:

- **Web** (`ui_run`) — a real HTML/CSS/JS page served by a tiny embedded HTTP
  server and opened in your browser.
- **Native** (`ui_run_native`) — a flat, modern desktop window (custom-painted
  rounded buttons, antialiased discs, dark card) — deliberately *not* the dated
  default Swing look.

Both are pure-JDK (no external dependencies). You describe the UI as a widget
tree and clicks call back into your Ow functions.

```yaml
fn render():
  return: ui_col([ui_heading("Counter"), ui_text("count = " + count),
                  ui_button("+1", "inc")])

fn inc():
  set count: count + 1

main:
  set count int: 0
  call: ui_run("render", "Counter")
```

UI builtins: `ui_heading`, `ui_text`, `ui_button(label, handler [, arg])`,
`ui_row`, `ui_col`, `ui_grid(cols, children)`, `ui_disc(color)`, `ui_run` (web),
`ui_run_web`, `ui_run_native`, `ui_stop`.

In **OwIDE** the toolbar has **UI Web ▶**, **UI Native ▶** and **Stop UI**
buttons — a plain `ui_run` program follows the button you pick (via the
`ow.ui.mode` system property / `OW_UI_MODE` env var). Set `OW_UI_NO_BROWSER=1` to
print the URL instead of auto-opening the browser for the web backend.

## Connect 4

The [`examples/`](examples) folder has a real, playable Connect 4 built on the
same `Board` class:

- [`connect4_console.ow`](examples/connect4_console.ow) — console version with an input loop.
- [`connect4_ui.ow`](examples/connect4_ui.ow) — modern clickable GUI: click a column to drop a piece.

## Components

| Path | Description |
|------|-------------|
| `src/main/java/dev/oreo/Lexer.java` | Tokenizer |
| `src/main/java/dev/oreo/Parser.java` | Parser → AST |
| `src/main/java/dev/oreo/Ast.java` | AST node definitions |
| `src/main/java/dev/oreo/Interpreter.java` | Tree-walking interpreter (OOP, switch, DB, UI builtins) |
| `src/main/java/dev/oreo/Json.java` | JSON reader/writer for database persistence |
| `src/main/java/dev/oreo/UiHost.java` | Common interface for UI backends |
| `src/main/java/dev/oreo/UiRuntime.java` | Embedded web UI server + renderer |
| `src/main/java/dev/oreo/SwingUiRuntime.java` | Modern flat native (desktop) UI renderer |
| `src/main/java/dev/oreo/LlvmIrGen.java` | LLVM IR code generator (procedural subset) |
| `src/main/java/dev/oreo/OwCompiler.java` | Native compiler entry point |
| `src/main/java/dev/oreo/Ide.java` | OwIDE |
| `src/main/java/dev/oreo/Main.java` | Main entry point |

## Building & running

Requires JDK 17+ and Maven.

```sh
mvn clean package            # builds target/OwIDE.jar
java -jar target/OwIDE.jar               # launches OwIDE
java -cp target/classes dev.oreo.Main --run examples/connect4_ui.ow   # run a program
```

The interpreter (`--run` / OwIDE "Run") supports the full language including OOP
and the UI runtime. The native **Build .exe** path (LLVM/`zig cc`) supports the
original procedural subset (functions, `if`/`while`, integers and strings).

## Releases

Prebuilt OwIDE jars and Windows installers are published on the
[Releases](../../releases) page — they are **not** committed to the repository.

## License

[MIT](LICENSE).
