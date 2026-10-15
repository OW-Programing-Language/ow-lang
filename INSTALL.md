# Installing Ow

Ow runs on the JVM, so installing it is like installing a JDK: unpack it and put
its `bin` on your `PATH`. After that, `ow` works from any terminal.

## Prerequisite

**Java 17 or newer** on your `PATH` (`java -version` should print 17+).

## Install (portable zip — recommended)

1. Download `ow-<version>-portable.zip` from the
   [Releases](https://github.com/OW-Programing-Language/ow-lang/releases) page.
2. Unzip it somewhere permanent, e.g. `C:\ow` or `~/ow`.
3. Add its `bin` directory to your `PATH`:

   **Windows (PowerShell, current user):**
   ```powershell
   setx PATH "$env:PATH;C:\ow\ow-<version>\bin"
   ```
   Then open a new terminal.

   **macOS / Linux:**
   ```sh
   echo 'export PATH="$HOME/ow/ow-<version>/bin:$PATH"' >> ~/.profile
   source ~/.profile
   # or: sudo ln -s "$HOME/ow/ow-<version>/bin/ow" /usr/local/bin/ow
   ```

4. Verify:
   ```sh
   ow version
   ow run examples/hello.ow
   ```

## Usage

```
ow <file.ow>            run a program (interpreter)
ow run <file.ow>        run a program
ow build <file> [out]   compile to a native .exe (needs LLVM/clang or Zig)
ow ide                  launch OwIDE
ow version
ow help
```

## Alternatives

- **Just the jar:** download `OwIDE.jar` and run `java -jar OwIDE.jar run app.ow`
  (or `java -jar OwIDE.jar` to open the IDE). The `ow` command is just a thin
  launcher around this.
- **Windows installer:** see `installer-template/` / `scripts/` for building an
  MSI/portable installer that bundles a runtime.

## Native executables

`ow build app.ow` compiles the procedural subset to a native `.exe` via
`zig cc` or `clang`. Bundle Zig at `./tools/zig/` or install LLVM — see
`OwCompiler` for the exact search paths.
