# Ow

Ow is a small, indentation-based programming language with a Java-hosted toolchain.
It ships with an interpreter, an LLVM IR backend (for compiling to native
executables), and a bundled IDE (**OwIDE**).

## Components

| Path | Description |
|------|-------------|
| `src/main/java/dev/oreo/Lexer.java` | Tokenizer |
| `src/main/java/dev/oreo/Parser.java` | Parser → AST |
| `src/main/java/dev/oreo/Ast.java` | AST node definitions |
| `src/main/java/dev/oreo/Interpreter.java` | Tree-walking interpreter |
| `src/main/java/dev/oreo/LlvmIrGen.java` | LLVM IR code generator |
| `src/main/java/dev/oreo/OwCompiler.java` | Compiler entry point |
| `src/main/java/dev/oreo/Ide.java` | OwIDE (Swing editor + runner) |
| `src/main/java/dev/oreo/Main.java` | Main entry point |

## Building

Requires JDK 17+ and Maven.

```sh
mvn clean package
```

This produces the runnable jar under `target/`.

## Running

Run a program with the interpreter, or compile it to LLVM IR and build a native
binary (an LLVM toolchain / `zig cc` is used as the linker — see `scripts/`).

Example programs live alongside the sources (`hello.ow`, `connect4_demo.ow`).

## Releases

Prebuilt OwIDE jars and Windows installers are published on the
[Releases](../../releases) page — they are **not** committed to the repository.
Build scripts for producing them are in `scripts/`, with packaging layouts in
`installer-template/` and `release-template/`.

## License

See repository for details.
