package dev.oreo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Main {

    public static final String VERSION = "1.2.2";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Ide.launch();
            return;
        }

        String cmd = args[0];
        switch (cmd) {
            case "ide", "--ide" -> Ide.launch();

            case "run", "--run" -> {
                requireFile(args, "run");
                runFile(Path.of(args[1]));
            }

            case "test" -> {
                requireFile(args, "test");
                Path target = Path.of(args[1]);
                String filter = null;
                if (args.length > 2) {
                    if (args.length != 4 || !"--filter".equals(args[2]) ||
                            !args[3].matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)?")) {
                        System.err.println("Usage: ow test <file|dir> [--filter TestName|ClassName.testMethod]");
                        System.exit(2);
                        return;
                    }
                    filter = args[3];
                }
                List<Path> tests = new ArrayList<>();
                if (Files.isDirectory(target)) {
                    try (Stream<Path> walk = Files.walk(target)) {
                        walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith("Test.ow")
                                      || p.getFileName().toString().endsWith("_test.ow"))
                            .sorted().forEach(tests::add);
                    }
                } else tests.add(target);
                if (tests.isEmpty()) {
                    System.err.println("No OW tests found in " + target);
                    System.exit(1);
                }
                int failures = 0;
                for (Path file : tests) {
                    System.out.println("Running OW tests: " + file);
                    failures += new Interpreter(System.out).runTests(Loader.loadTests(file), filter);
                }
                if (failures > 0) System.exit(1);
            }

            case "check", "--check" -> {
                requireFile(args, "check");
                Ast.Program program = Loader.load(Path.of(args[1]));
                ModuleTypeChecker.check(program);
                System.out.println("OW module type check passed: " +
                                   Path.of(args[1]).toAbsolutePath());
            }

            case "llvm", "emit-llvm" -> {
                requireFile(args, "llvm");
                Path source = Path.of(args[1]);
                Path output = args.length >= 3
                        ? Path.of(args[2])
                        : Path.of(stripExt(source.getFileName().toString()) + ".ll");
                new OwCompiler().emitLlvm(source, output);
                System.out.println("LLVM IR: " + output.toAbsolutePath());
            }

            case "build" -> {
                requireFile(args, "build");
                Path in = Path.of(args[1]);
                Path out = (args.length >= 3)
                        ? Path.of(args[2])
                        : Path.of(stripExt(in.getFileName().toString()) + ".exe");
                new OwCompiler().compileToExe(in, out);
                System.out.println("Built: " + out.toAbsolutePath());
            }

            case "package", "exe" -> {
                requireFile(args, "package");
                packageApp(args);
            }

            case "deps" -> DependencyManager.command(args);

            case "version", "--version", "-v" -> System.out.println("ow " + VERSION);

            case "help", "--help", "-h" -> printUsage();

            default -> {
                // Bare file: `ow app.ow` runs it; legacy single-arg path also runs.
                Path p = Path.of(cmd);
                if (cmd.endsWith(".ow") || Files.isRegularFile(p)) {
                    runFile(p);
                } else {
                    System.err.println("Unknown command: " + cmd);
                    printUsage();
                    System.exit(1);
                }
            }
        }
    }

    private static void runFile(Path in) throws Exception {
        // Loader resolves `import "..."` across files, then merges them.
        new Interpreter(System.out).run(Loader.load(in));
    }

    private static void packageApp(String[] args) throws Exception {
        Path entry = Path.of(args[1]);
        String name = null, type = null, version = null;
        Path out = null, icon = null;
        for (int i = 2; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--name" -> name = args[++i];
                case "--type" -> type = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--icon" -> icon = Path.of(args[++i]);
                case "--version" -> version = args[++i];
                default -> { /* ignore unknown */ }
            }
        }
        if (type != null && !type.equals("gui") && !type.equals("console") && !type.equals("web")) {
            System.err.println("--type must be gui, console, or web");
            System.exit(1);
        }
        Packager.build(entry, name, type, out, icon, version);
    }

    private static void requireFile(String[] args, String cmd) {
        if (args.length < 2) {
            System.err.println("Usage: ow " + cmd + " <file.ow>");
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("""
                ow %s — the Ow programming language

                Usage:
                  ow <file.ow>          Run a program (interpreter)
                  ow run <file.ow>      Run a program (interpreter)
                  ow check <file.ow>    Validate module/functor types without executing main
                  ow test <file|dir> [--filter name]  Run OW tests, optionally one function or Class.method
                  ow llvm <file> [out]  Generate LLVM IR for the i32/static-module subset
                  ow build <file> [out] Compile supported OW programs (needs LLVM/Zig)
                  ow deps init [dir]      Create a project dependencies.yml
                  ow deps install [dir]   Download libraries declared in dependencies.yml
                  ow deps update [dir]    Refresh the dependency lockfile
                  ow deps list [dir]      List declared OW libraries
                  ow package <file>     Bundle an app/game into a launchable .exe + installer
                                        [--name N] [--type gui|console|web] [--out dir] [--icon x.ico]
                  ow ide                Launch OwIDE
                  ow version            Print the version
                  ow help               Show this help

                Examples:
                  ow hello.ow
                  ow run examples/api_demo.ow
                  ow build hello.ow hello.exe
                """.formatted(VERSION));
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return (i >= 0) ? s.substring(0, i) : s;
    }
}
