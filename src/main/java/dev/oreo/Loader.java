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
        List<Ast.ModuleTypeDecl> moduleTypes = new ArrayList<>();
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.FunctorDecl> functors = new ArrayList<>();
        List<Ast.FunctorTypeDecl> functorTypes = new ArrayList<>();

        Set<Path> visited = new HashSet<>();
        Deque<Path> active = new ArrayDeque<>();
        List<Ast.Stmt> mainBody = collect(root, visited, fns, enums, interfaces, classes, models, moduleTypes, modules, functors, functorTypes, active, true);

        if (mainBody == null) {
            throw new RuntimeException("Program is missing a 'main:' block (entry file: " + root + ")");
        }
        return new Ast.Program(List.of(), fns, enums, interfaces, classes, models,
                               moduleTypes, modules, functors, functorTypes, mainBody);
    }

    /** Loads an entry from in-memory source (e.g. an editor buffer), resolving
     *  its imports relative to {@code baseDir}. Used by OwIDE so unsaved edits run. */
    public static Ast.Program loadSource(String src, Path baseDir) throws IOException {
        List<Ast.FnDecl> fns = new ArrayList<>();
        List<Ast.EnumDecl> enums = new ArrayList<>();
        List<Ast.InterfaceDecl> interfaces = new ArrayList<>();
        List<Ast.ClassDecl> classes = new ArrayList<>();
        List<Ast.ModelDecl> models = new ArrayList<>();
        List<Ast.ModuleTypeDecl> moduleTypes = new ArrayList<>();
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.FunctorDecl> functors = new ArrayList<>();
        List<Ast.FunctorTypeDecl> functorTypes = new ArrayList<>();

        Ast.Program entry = new Parser(new Lexer(src).lexAll()).parseProgram();
        Set<Path> visited = new HashSet<>();
        Deque<Path> active = new ArrayDeque<>();
        for (String imp : entry.imports) {
            Path target = baseDir.resolve(imp).normalize();
            if (!Files.exists(target)) {
                throw new RuntimeException("import not found: \"" + imp + "\"");
            }
            collect(target, visited, fns, enums, interfaces, classes, models, moduleTypes, modules, functors, functorTypes, active, false);
        }
        fns.addAll(entry.functions);
        enums.addAll(entry.enums);
        interfaces.addAll(entry.interfaces);
        classes.addAll(entry.classes);
        models.addAll(entry.models);
        moduleTypes.addAll(entry.moduleTypes);
        modules.addAll(entry.modules);
        functors.addAll(entry.functors);
        functorTypes.addAll(entry.functorTypes);

        if (entry.mainBody == null) {
            throw new RuntimeException("Program is missing a 'main:' block");
        }
        return new Ast.Program(List.of(), fns, enums, interfaces, classes, models,
                               moduleTypes, modules, functors, functorTypes, entry.mainBody);
    }

    private static List<Ast.Stmt> collect(Path file, Set<Path> visited,
                                           List<Ast.FnDecl> fns, List<Ast.EnumDecl> enums,
                                           List<Ast.InterfaceDecl> interfaces, List<Ast.ClassDecl> classes,
                                           List<Ast.ModelDecl> models,
                                           List<Ast.ModuleTypeDecl> moduleTypes,
                                           List<Ast.ModuleDecl> modules,
                                           List<Ast.FunctorDecl> functors,
                                           List<Ast.FunctorTypeDecl> functorTypes, Deque<Path> active,
                                           boolean isEntry) throws IOException {
        Path norm = file.toAbsolutePath().normalize();
        if (active.contains(norm)) {
            throw new RuntimeException("Import cycle: " + active + " -> " + norm);
        }
        if (!visited.add(norm)) return null; // already loaded
        active.addLast(norm);

        String src = Files.readString(norm, StandardCharsets.UTF_8);
        Ast.Program prog = new Parser(new Lexer(src).lexAll()).parseProgram();

        // Load imports first (depth-first), resolved relative to this file.
        for (String imp : prog.imports) {
            Path target = norm.getParent().resolve(imp).normalize();
            int line = sourceImportLine(src, imp);
            if (!Files.exists(target)) {
                throw new RuntimeException(norm + ":" + line + ": import not found: \"" + imp + "\"");
            }
            checkImportBoundary(norm, target, line);
            collect(target, visited, fns, enums, interfaces, classes, models, moduleTypes, modules, functors, functorTypes, active, false);
        }

        fns.addAll(prog.functions);
        enums.addAll(prog.enums);
        interfaces.addAll(prog.interfaces);
        classes.addAll(prog.classes);
        models.addAll(prog.models);
        moduleTypes.addAll(prog.moduleTypes);
        modules.addAll(prog.modules);
        functors.addAll(prog.functors);
        functorTypes.addAll(prog.functorTypes);

        if (!isEntry && prog.mainBody != null) {
            throw new RuntimeException("imported file must not declare 'main': " + norm);
        }
        active.removeLast();
        return prog.mainBody;
    }

    private record Domain(String root, String zone) {}

    /**
     * A conventional business module contains api/ and infra/ source folders.
     * The app composition root may import infra; members of one business module
     * may only import other modules' public APIs. API code may not import infra.
     */
    private static Domain domainOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        for (int i = 0; i + 2 < absolute.getNameCount(); i++) {
            String zone = absolute.getName(i + 1).toString();
            if (zone.equals("api") || zone.equals("infra")) {
                return new Domain(absolute.subpath(0, i + 1).toString(), zone);
            }
        }
        return null;
    }

    private static void checkImportBoundary(Path source, Path target, int line) {
        Domain origin = domainOf(source);
        Domain destination = domainOf(target);
        if (origin == null || destination == null || !destination.zone.equals("infra")) return;
        if (origin.zone.equals("api") || !origin.root.equals(destination.root)) {
            throw new RuntimeException(source + ":" + line +
                    ": module boundary violation: cannot import implementation " + target +
                    " from " + origin.zone + "; import the target module's api/ instead");
        }
    }

    private static int sourceImportLine(String source, String imported) {
        String[] lines = source.split("\\n", -1);
        String needle = "import \"" + imported + "\"";
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals(needle)) return i + 1;
        }
        return 1;
    }
}
