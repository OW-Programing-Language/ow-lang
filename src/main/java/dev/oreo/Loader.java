package dev.oreo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves {@code import "other.ow"} statements and merges all referenced files
 * into a single {@link Ast.Program}. Imports are resolved relative to the file
 * that declares them; each file is loaded at most once. The entry file supplies
 * {@code main}; imported files contribute their classes / functions / enums /
 * models (and should not declare {@code main}).
 */
public final class Loader {
    private Loader() {}

    public static Ast.Program load(Path entry) throws IOException {
        Path root = entry.toAbsolutePath().normalize();

        List<Ast.FnDecl> fns = new ArrayList<>();
        List<Ast.EnumDecl> enums = new ArrayList<>();
        List<Ast.InterfaceDecl> interfaces = new ArrayList<>();
        List<Ast.ClassDecl> classes = new ArrayList<>();
        List<Ast.ModelDecl> models = new ArrayList<>();

        Set<Path> visited = new HashSet<>();
        List<Ast.Stmt> mainBody = collect(root, visited, fns, enums, interfaces, classes, models, true);

        if (mainBody == null) {
            throw new RuntimeException("Program is missing a 'main:' block (entry file: " + root + ")");
        }
        return new Ast.Program(List.of(), fns, enums, interfaces, classes, models, mainBody);
    }

    /** Loads an entry from in-memory source (e.g. an editor buffer), resolving
     *  its imports relative to {@code baseDir}. Used by OwIDE so unsaved edits run. */
    public static Ast.Program loadSource(String src, Path baseDir) throws IOException {
        List<Ast.FnDecl> fns = new ArrayList<>();
        List<Ast.EnumDecl> enums = new ArrayList<>();
        List<Ast.InterfaceDecl> interfaces = new ArrayList<>();
        List<Ast.ClassDecl> classes = new ArrayList<>();
        List<Ast.ModelDecl> models = new ArrayList<>();

        Ast.Program entry = new Parser(new Lexer(src).lexAll()).parseProgram();
        Set<Path> visited = new HashSet<>();
        for (String imp : entry.imports) {
            Path target = baseDir.resolve(imp).normalize();
            if (!Files.exists(target)) {
                throw new RuntimeException("import not found: \"" + imp + "\"");
            }
            collect(target, visited, fns, enums, interfaces, classes, models, false);
        }
        fns.addAll(entry.functions);
        enums.addAll(entry.enums);
        interfaces.addAll(entry.interfaces);
        classes.addAll(entry.classes);
        models.addAll(entry.models);

        if (entry.mainBody == null) {
            throw new RuntimeException("Program is missing a 'main:' block");
        }
        return new Ast.Program(List.of(), fns, enums, interfaces, classes, models, entry.mainBody);
    }

    private static List<Ast.Stmt> collect(Path file, Set<Path> visited,
                                           List<Ast.FnDecl> fns, List<Ast.EnumDecl> enums,
                                           List<Ast.InterfaceDecl> interfaces, List<Ast.ClassDecl> classes,
                                           List<Ast.ModelDecl> models, boolean isEntry) throws IOException {
        Path norm = file.toAbsolutePath().normalize();
        if (!visited.add(norm)) return null; // already loaded

        String src = Files.readString(norm, StandardCharsets.UTF_8);
        Ast.Program prog = new Parser(new Lexer(src).lexAll()).parseProgram();

        // Load imports first (depth-first), resolved relative to this file.
        for (String imp : prog.imports) {
            Path target = norm.getParent().resolve(imp).normalize();
            if (!Files.exists(target)) {
                throw new RuntimeException("import not found: \"" + imp + "\" (from " + norm + ")");
            }
            collect(target, visited, fns, enums, interfaces, classes, models, false);
        }

        fns.addAll(prog.functions);
        enums.addAll(prog.enums);
        interfaces.addAll(prog.interfaces);
        classes.addAll(prog.classes);
        models.addAll(prog.models);

        if (!isEntry && prog.mainBody != null) {
            throw new RuntimeException("imported file must not declare 'main': " + norm);
        }
        return prog.mainBody;
    }
}
