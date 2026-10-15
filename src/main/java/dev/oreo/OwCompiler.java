package dev.oreo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class OwCompiler {

    private static final class BuildToolchain {
        final String name;
        final List<String> command;

        BuildToolchain(String name, List<String> command) {
            this.name = name;
            this.command = command;
        }
    }

    public void compileToExe(Path inputOw, Path outputExe) throws Exception {
        String src = Files.readString(inputOw, StandardCharsets.UTF_8);

        // 1) lex + parse
        Lexer lexer = new Lexer(src);
        List<Lexer.Token> tokens = lexer.lexAll();
        Parser parser = new Parser(tokens);
        Ast.Program program = parser.parseProgram();

        // 2) generate LLVM IR
        String moduleName = outputExe.getFileName().toString().replace('.', '_');
        String ir = new LlvmIrGen().generate(program, moduleName);

        // 3) write .ll next to exe
        Path llPath = outputExe.resolveSibling(stripExt(outputExe.getFileName().toString()) + ".ll");
        Files.writeString(llPath, ir, StandardCharsets.UTF_8);

        // 4) call the selected native toolchain to build exe
        runNativeBuild(llPath, outputExe);

        // optional: keep .ll (we keep it by default)
    }

    private void runNativeBuild(Path llPath, Path exePath) throws IOException, InterruptedException {
        BuildToolchain toolchain = resolveBuildToolchain(llPath, exePath);
        ProcessBuilder pb = new ProcessBuilder(toolchain.command);
        configureToolchainEnvironment(pb, toolchain, exePath);
        pb.inheritIO();
        Process p;
        try {
            p = pb.start();
        } catch (IOException ex) {
            throw new IOException("Could not start " + toolchain.name + ". " + clangSearchHint(), ex);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException(toolchain.name + " failed with exit code " + code);
        }
    }

    public static String clangSearchHint() {
        return "Best portable option: bundle Zig in ./tools/zig/zig"
                + executableSuffix()
                + " (or set OW_ZIG / OW_ZIG_HOME). "
                + "LLVM also works from ./llvm/bin/clang"
                + executableSuffix()
                + " or ./tools/llvm/bin/clang"
                + executableSuffix()
                + " (or set OW_CLANG / OW_LLVM_HOME), but plain LLVM on Windows may still depend on MSVC + Windows SDK.";
    }

    private static BuildToolchain resolveBuildToolchain(Path llPath, Path exePath) {
        String zig = resolveZigExecutable();
        if (zig != null) {
            return new BuildToolchain("zig cc", Arrays.asList(
                    zig,
                    "cc",
                    "-target", "x86_64-windows-gnu",
                    llPath.toAbsolutePath().toString(),
                    "-O2",
                    "-o", exePath.toAbsolutePath().toString()
            ));
        }

        return new BuildToolchain("clang", Arrays.asList(
                resolveClangExecutable(),
                llPath.toAbsolutePath().toString(),
                "-O2",
                "-o", exePath.toAbsolutePath().toString()
        ));
    }

    private static void configureToolchainEnvironment(ProcessBuilder pb, BuildToolchain toolchain, Path exePath)
            throws IOException {
        if (!toolchain.name.equals("zig cc")) {
            return;
        }

        String customGlobal = System.getenv("OW_ZIG_GLOBAL_CACHE_DIR");
        String customLocal = System.getenv("OW_ZIG_LOCAL_CACHE_DIR");

        Path globalCache;
        Path localCache;
        if (customGlobal != null && !customGlobal.isBlank() && customLocal != null && !customLocal.isBlank()) {
            globalCache = Path.of(customGlobal);
            localCache = Path.of(customLocal);
            Files.createDirectories(globalCache);
            Files.createDirectories(localCache);
        } else {
            Path cacheBase = ensureUsableCacheBase();
            globalCache = customGlobal != null && !customGlobal.isBlank()
                    ? Path.of(customGlobal)
                    : cacheBase.resolve("global");
            localCache = customLocal != null && !customLocal.isBlank()
                    ? Path.of(customLocal)
                    : cacheBase.resolve("local");
            Files.createDirectories(globalCache);
            Files.createDirectories(localCache);
        }

        pb.environment().put("ZIG_GLOBAL_CACHE_DIR", globalCache.toAbsolutePath().toString());
        pb.environment().put("ZIG_LOCAL_CACHE_DIR", localCache.toAbsolutePath().toString());
    }

    private static String resolveZigExecutable() {
        String envZig = System.getenv("OW_ZIG");
        if (envZig != null && !envZig.isBlank()) {
            return envZig;
        }

        for (Path candidate : zigCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        return null;
    }

    private static String resolveClangExecutable() {
        String envClang = System.getenv("OW_CLANG");
        if (envClang != null && !envClang.isBlank()) {
            return envClang;
        }

        for (Path candidate : clangCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }

        return "clang";
    }

    private static List<Path> zigCandidates() {
        List<Path> candidates = new ArrayList<>();
        String exe = "zig" + executableSuffix();

        String zigHome = System.getenv("OW_ZIG_HOME");
        if (zigHome != null && !zigHome.isBlank()) {
            candidates.add(Path.of(zigHome).resolve(exe));
        }

        for (Path base : baseDirectories()) {
            candidates.add(base.resolve("tools").resolve("zig").resolve(exe));
            candidates.add(base.resolve("zig").resolve(exe));
            candidates.add(base.resolve("bin").resolve(exe));
            candidates.add(base.resolve(exe));
        }
        return candidates;
    }

    private static List<Path> clangCandidates() {
        List<Path> candidates = new ArrayList<>();
        String exe = "clang" + executableSuffix();

        String llvmHome = System.getenv("OW_LLVM_HOME");
        if (llvmHome != null && !llvmHome.isBlank()) {
            candidates.add(Path.of(llvmHome).resolve("bin").resolve(exe));
        }

        for (Path base : baseDirectories()) {
            candidates.add(base.resolve("llvm").resolve("bin").resolve(exe));
            candidates.add(base.resolve("tools").resolve("llvm").resolve("bin").resolve(exe));
            candidates.add(base.resolve("bin").resolve(exe));
            candidates.add(base.resolve(exe));
        }
        return candidates;
    }

    private static List<Path> baseDirectories() {
        List<Path> bases = new ArrayList<>();
        bases.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());

        Path appDir = detectAppDirectory();
        if (appDir != null) {
            Path normalized = appDir.toAbsolutePath().normalize();
            if (!bases.contains(normalized)) bases.add(normalized);
            Path parent = normalized.getParent();
            if (parent != null && !bases.contains(parent)) bases.add(parent);
        }

        return bases;
    }

    private static Path ensureUsableCacheBase() throws IOException {
        for (Path candidate : defaultZigCacheBases()) {
            try {
                Files.createDirectories(candidate);
                return candidate;
            } catch (IOException ignored) {
            }
        }
        throw new IOException("Could not create a writable Zig cache directory");
    }

    private static List<Path> defaultZigCacheBases() {
        List<Path> bases = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            bases.add(Path.of(localAppData).resolve("OwIDE").resolve("zig-cache"));
        }
        bases.add(Path.of(System.getProperty("java.io.tmpdir")).resolve("OwIDE-zig-cache"));
        return bases;
    }

    private static Path detectAppDirectory() {
        try {
            Path location = Path.of(OwCompiler.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.isRegularFile(location)) {
                return location.getParent();
            }
            return location;
        } catch (URISyntaxException | NullPointerException ex) {
            return null;
        }
    }

    private static String executableSuffix() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win") ? ".exe" : "";
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return (i >= 0) ? s.substring(0, i) : s;
    }
}
