package dev.oreo;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--ide")) {
            Ide.launch();
            return;
        }

        if (args[0].equals("--run")) {
            if (args.length < 2) {
                System.out.println("Usage: owc --run <input.ow>");
                System.exit(1);
            }
            Path in = Path.of(args[1]);
            String src = java.nio.file.Files.readString(in);
            Lexer lexer = new Lexer(src);
            Parser parser = new Parser(lexer.lexAll());
            new Interpreter(System.out).run(parser.parseProgram());
            return;
        }

        Path input = Path.of(args[0]);
        Path outExe = (args.length >= 2)
                ? Path.of(args[1])
                : Path.of(stripExt(input.getFileName().toString()) + ".exe");

        new OwCompiler().compileToExe(input, outExe);
        System.out.println("Built: " + outExe.toAbsolutePath());
    }


    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return (i >= 0) ? s.substring(0, i) : s;
    }
}