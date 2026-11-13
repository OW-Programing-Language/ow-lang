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
- **Imports (multi-file projects)** — `import "Board.ow"` merges another file's
  classes/functions/models (resolved relative to the importing file). Put one
  class per file; the entry file holds `main`.
- **Method event handlers** — UI/web handlers can target a method on a global
  object via a dotted name, e.g. `ui_button("X", "game.move", i)` or
  `ui_run("game.view", ...)` — so UI apps can be fully object-oriented.
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

## Web backend

Ow can serve HTTP directly — an embedded, dependency-free server with inline
route handlers. Combined with the built-in database, a `model` becomes a REST
API in a handful of lines.

```yaml
model Note:
  title string
  body string

main:
  route "GET /notes":
    respond: find Note

  route "GET /notes/:id":
    set want int: int(param("id"))
    respond: (find Note where id == want)[0]

  route "POST /notes":
    save Note: body_json()
    respond: response(201, {created: true})

  serve: 8080
```

- `route "METHOD /path/:param": <block>` — register a handler (method defaults to GET; `:param` captures path segments).
- Inside a handler: `req` (record with `method`/`path`/`query`/`params`/`body`), plus `param(name)`, `query(name)`, `body()`, `body_json()`.
- `respond: value` — a record/array becomes JSON, a string becomes text/HTML; `response(status, value)` sets a status code.
- `serve: port` — start the server (blocks).
- JSON helpers: `json(value)`, `json_parse(string)`.

See [`examples/api_demo.ow`](examples/api_demo.ow). The **Night** web framework
(https://github.com/OW-Programing-Language/night) builds higher-level ergonomics
on top of these primitives.

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

UI builtins: `ui_heading(text [, color])`, `ui_text(text [, color])`,
`ui_button(label, handler [, arg])`, `ui_image(src [, width, height])`,
`ui_bar(value, max [, color])`, `ui_space([size])`, `ui_card(children [, color])`,
`ui_backdrop(src, child)`, `ui_row`, `ui_col`, `ui_grid(cols, children)`,
`ui_disc(color)`, `ui_run` (web), `ui_run_web`, `ui_run_native`, `ui_stop`.

`ui_text`/`ui_heading` take an optional colour (a name like `"red"`/`"green"` or
a `#rrggbb` hex). `ui_image` takes an optional size. `ui_bar` draws a rounded
progress bar (health/XP), `ui_space` inserts vertical spacing, `ui_card` wraps
widgets in a rounded panel, and `ui_backdrop` fills the window with a background
image behind a centered child. All render in both the web and native backends.

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
| `src/main/java/dev/oreo/Json.java` | JSON reader/writer (database + web) |
| `src/main/java/dev/oreo/WebRuntime.java` | Embedded HTTP server (route/serve) |
| `src/main/java/dev/oreo/UiHost.java` | Common interface for UI backends |
| `src/main/java/dev/oreo/UiRuntime.java` | Embedded web UI server + renderer |
| `src/main/java/dev/oreo/SwingUiRuntime.java` | Modern flat native (desktop) UI renderer |
| `src/main/java/dev/oreo/LlvmIrGen.java` | LLVM IR code generator (procedural subset) |
| `src/main/java/dev/oreo/OwCompiler.java` | Native compiler entry point |
| `src/main/java/dev/oreo/Ide.java` | OwIDE |
| `src/main/java/dev/oreo/Main.java` | Main entry point |

## Installing (use `ow` everywhere)

Install Ow like a JDK: download the portable zip from
[Releases](../../releases), unzip it, and add its `bin/` to your `PATH`. Then:

```sh
ow version
ow hello.ow                 # run a program
ow run examples/api_demo.ow
ow build hello.ow           # native .exe (needs LLVM/clang or Zig)
ow ide                      # launch OwIDE
```

Full instructions (Windows / macOS / Linux): [INSTALL.md](INSTALL.md).

## Building from source

Requires JDK 17+ and Maven.

```sh
mvn clean package            # builds target/OwIDE.jar
java -jar target/OwIDE.jar               # launches OwIDE
java -jar target/OwIDE.jar run examples/connect4_ui.ow   # run a program
```

The interpreter (`--run` / OwIDE "Run") supports the full language including OOP
and the UI runtime. The native **Build .exe** path (LLVM/`zig cc`) supports the
original procedural subset (functions, `if`/`while`, integers and strings).

## Packaging a standalone app

With a full JDK installed, build the runtime jar above, then:

```sh
java -jar target/OwIDE.jar package path/to/src/main_ui.ow --name MyGame --type gui --out dist
# Or use: ow package path/to/src/main_ui.ow --name MyGame --type gui --out dist
```

The app-image bundles Java, the `.ow` files under the entry directory, and
recursive `assets/` and `models/` directories. Resources are taken from beside
the entry, or from the project root when the entry is under `src/`; entry-local
resource directories take precedence. Other directories and saves are not copied.
The chosen entry is recorded in `game/entry.txt`, so `main_ui.ow` still launches
when `main.ow` also exists. Older packages without that file default to `main.ow`.

Use `--type console` for terminal input or `--type gui` for native desktop UI.
Native images retain GIF animation and fit within 240 by 180 pixels.
Relative image, audio, model and save paths resolve against the bundled `game/`
directory, regardless of the launch working directory. Keep the app-image in a
writable location if the program saves progress. Distribute the whole app folder,
including its runtime, rather than the executable alone. Models must be supplied
before packaging; the packager does not download them.

## Releases

Prebuilt OwIDE jars and Windows installers are published on the
[Releases](../../releases) page — they are **not** committed to the repository.

## License

[MIT](LICENSE).

## Interface conformance validation

Ow now verifies interface declarations **before executing `main`**. A class
that declares `class C(Shape)` must implement all public methods listed in
`interface Shape`, with the expected number of arguments. Missing members,
private implementations, wrong arity, unknown parent types, and inheritance
cycles produce errors before program execution. Inherited public methods count.

This strengthens module contracts used by the companion
[flOW framework](https://github.com/OW-Programing-Language/flOW---OW-Modular-Framework).

**Typed method signatures:** Add optional parameter and return annotations in
interfaces and class methods:

```ow
interface Storage:
  fn put(key string, value int) -> bool
class MemoryStorage(Storage):
  fn put(key string, value int) -> bool:
    return: true
```

OW rejects mismatched parameter or return types and unknown type names before
`main`, and checks actual arguments and results at method call boundaries. Annotations are
optional, so existing OW programs continue working.

**Current limit:** This validates explicitly declared method signatures at
interpreter load time. It is not an OCaml module compiler, abstract type system
or full static inference engine.

## Native module types and functors (experimental)

OW now has native module-language declarations (`module_type`, `module`,
`functor`) with structural signature validation before `main`. Module
applications pass module values, not `App` objects, and callable exports are
restricted by the declared signature.

Try [the module calculus example](examples/module_calculus/README.md), and
read [the implementation and limitations](docs/native-modules.md). These
features advance [issue #16](https://github.com/OW-Programing-Language/ow-lang/issues/16),
but abstract types, sharing constraints and generativity remain outstanding.

### Opaque module type members

`module_type BOX` may declare `type token`; an implementation can define
`type token: int` and return or consume `token` through public functions.
The runtime seals the concrete integer into an opaque module-specific value,
preventing callers from mixing values from different BOX modules. Try
[abstract_types.ow](examples/module_calculus/abstract_types.ow). Type sharing
constraints and generative/applicative identities are still being developed.

### Applicative and generative functors

`functor Make(S: SOURCE) -> BOX` reuses abstract type identities for repeated
applications with the same stable module argument. For explicit generativity,
`functor_gen Fresh(S: SOURCE) -> BOX` creates fresh identities on each call.
See [functor_identity.ow](examples/module_calculus/functor_identity.ow).

### Explicit manifest types

A `module_type` may expose `type token: int` to make its representation
public, unlike a sealed `type token`. Modules must agree on the concrete type
and can exchange values safely across this shared interface. This covers
manifest type sharing, not OCaml's complete `with type` path equations.
See [manifest_sharing.ow](examples/module_calculus/manifest_sharing.ow).

## Compiler-enforced api/infra boundaries

OW now rejects imports from another business module's `infra/` directory and
rejects `api/` imports of `infra/`. Import cycles and missing imports produce
source-path/line diagnostics. See [module boundaries](docs/module-boundaries.md).

### Path-dependent functor results

`functor Forward(S: BOX) -> BOX with_type token = S.token` now ties the
result module's abstract `token` to the input module's token identity.
This is validated at load time and enforced at module call boundaries. See
[dependent_result.ow](examples/module_calculus/dependent_result.ow).

### Higher-order functors

`functor_type MAKER(SOURCE) -> RESULT` declares a functor signature.
`functor Decorate(F: MAKER) -> RESULT` can receive another functor; a
functor returning `MAKER` uses `return_functor: F` to expose a compatible
functor value. See [higher_order.ow](examples/module_calculus/higher_order.ow).

### Static abstract-type identity checks

OW now checks statically known module values before `main`: passing a token
from another module, mixing generative identities or leaking an opaque token
into arithmetic is rejected before the program runs. Unknown dynamic values
remain guarded by runtime checks. See [native module checks](docs/native-modules.md).

## Named module type sharing

`module Alias: BOX with_type token = First.token` shares an opaque type identity
between named modules. See [named type sharing](examples/module_calculus/named_type_sharing.ow).

## OCaml OrderedType / Set.Make comparison

[examples/module_calculus/set_make.ow](examples/module_calculus/set_make.ow)
shows a typed Order module and a MakeSet functor with an abstract element
identity, executable membership and add operations, and negative fixtures.
Compiler diagnostics now carry file/line/column spans for module type errors.

**Compile-time validation:** `ow check examples/module_calculus/set_make.ow`
checks module signatures and abstract types before execution. The LLVM backend
remains procedural-only and rejects native module code explicitly.
