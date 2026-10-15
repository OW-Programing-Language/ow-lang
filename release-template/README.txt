Ow IDE Portable Bundle
======================

Goal:
Let users run Ow IDE and build .exe files without installing LLVM manually.

Recommended layout:
OwIDE.jar
Run-OwIDE.bat
tools\
  zig\
    zig.exe
    lib\
    ...

Why Zig:
The old clang-on-PATH flow worked on the developer machine, but on Windows it also
depends on MSVC and Windows SDK libraries. A bundled Zig toolchain is much more portable.

How the IDE resolves the native compiler:
1. OW_ZIG
2. OW_ZIG_HOME\zig.exe
3. .\tools\zig\zig.exe
4. OwIDE falls back to LLVM / clang if available

After you place Zig into tools\zig, users can usually build .exe directly from the IDE.
