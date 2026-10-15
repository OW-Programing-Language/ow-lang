package dev.oreo;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static final String VERSION = "1.2.0";

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

            case "build" -> {
                requireFile(args, "build");
                Path in = Path.of(args[1]);
                Path out = (args.length >= 3)
                        ? Path.of(args[2])
                        : Path.of(stripExt(in.getFileName().toString()) + ".exe");
                new OwCompiler().compileToExe(in, out);
                System.out.println("Built: " + out.toAbsolutePath());
            }

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
        String src = Files.readString(in);
        Lexer lexer = new Lexer(src);
        Parser parser = new Parser(lexer.lexAll());
        new Interpreter(System.out).run(parser.parseProgram());
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
                  ow build <file> [out] Compile to a native .exe (needs LLVM/clang or Zig)
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
