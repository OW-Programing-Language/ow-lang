package dev.oreo;

import java.util.*;

/**
 * Resolve implementation-level native module includes before type checking.
 *
 * An included export delegates to its *original* module rather than copying
 * executable statements. This preserves the included module's opaque type
 * identity, functor captures and privacy/sealing boundary.
 *
 * The resulting Program contains ordinary module/functor declarations so
 * every existing verifier and interpreter path checks the expanded exports.
 */
public final class ModuleImplementationExpander {
    private final Map<String, Ast.ModuleDecl> modules = new LinkedHashMap<>();
    private final Map<String, Ast.ModuleDecl> expanded = new LinkedHashMap<>();
    private final Map<String, Ast.ModuleTypeDecl> signatures;
    private final Map<String, String> aliases;
    private final Set<String> active = new LinkedHashSet<>();

    private ModuleImplementationExpander(Ast.Program program) {
        signatures = ModuleTypeChecker.signatures(program);
        aliases = ModuleTypeChecker.resolvedAliases(program);
        for (Ast.ModuleDecl mod : program.modules) {
            if (modules.putIfAbsent(mod.name, mod) != null) {
                throw new RuntimeException("Duplicate module " + mod.name);
            }
        }
    }

    public static Ast.Program expand(Ast.Program program) {
        program = LexicalModuleNormalizer.normalize(program);
        boolean required = program.modules.stream().anyMatch(m -> !m.includes.isEmpty()) ||
                program.functors.stream().anyMatch(f -> !f.includes.isEmpty());
        if (!required) return program;
        ModuleImplementationExpander resolver = new ModuleImplementationExpander(program);
        List<Ast.ModuleDecl> mods = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules) {
            mods.add(resolver.expandModule(module.name));
        }
        List<Ast.FunctorDecl> functors = new ArrayList<>();
        for (Ast.FunctorDecl functor : program.functors) {
            if (functor.includes.isEmpty()) {
                functors.add(functor);
                continue;
            }
            ResolvedMembers members = resolver.resolve(functor.name,
                    functor.outputSignature, functor.methods, functor.typeAliases,
                    functor.includes, functor.argument, functor.inputSignature);
            functors.add(new Ast.FunctorDecl(functor.name, functor.argument,
                    functor.inputSignature, functor.outputSignature, members.methods,
                    members.typeAliases, functor.generative, functor.typeSharing,
                    functor.returnedFunctor, List.of(), functor.span));
        }
        return new Ast.Program(program.imports, program.functions, program.enums,
                program.interfaces, program.classes, program.models,
                program.moduleTypes, mods, functors, program.functorTypes,
                program.moduleAliases, program.mainBody);
    }

    private Ast.ModuleDecl expandModule(String name) {
        if (expanded.containsKey(name)) return expanded.get(name);
        Ast.ModuleDecl module = modules.get(name);
        if (module == null) throw new RuntimeException("Unknown implementation include " + name);
        if (!active.add(name)) {
            throw new RuntimeException("Cyclic implementation include: " + active + " -> " + name);
        }
        ResolvedMembers members = resolve(name, module.signature, module.methods,
                module.typeAliases, module.includes, null, null);
        active.remove(name);
        Ast.ModuleDecl result = new Ast.ModuleDecl(module.name, module.signature,
                members.methods, members.typeAliases, module.typeSharing, List.of(),
                List.of(), List.of(), module.lexicalChildren, module.span);
        expanded.put(name, result);
        return result;
    }

    private record ResolvedMembers(List<Ast.MethodDecl> methods,
                                   Map<String, String> typeAliases) {}

    private ResolvedMembers resolve(String owner, String destinationSignature,
                                     List<Ast.MethodDecl> localMethods,
                                     Map<String, String> localTypes,
                                     List<String> includes, String functorParameter,
                                     String parameterSignature) {
        Map<String, Ast.MethodDecl> methods = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        Ast.ModuleTypeDecl destination = signatures.get(destinationSignature);
        if (destination == null) {
            throw new RuntimeException(owner + " uses unknown signature " + destinationSignature);
        }

        for (String sourceName : includes) {
            Ast.ModuleTypeDecl sourceSignature;
            if (functorParameter != null && sourceName.equals(functorParameter)) {
                sourceSignature = signatures.get(parameterSignature);
                if (sourceSignature == null) {
                    throw new RuntimeException("Cannot include a higher-order functor " +
                            sourceName + " as a module implementation");
                }
            } else {
                String resolved = aliases.getOrDefault(sourceName, sourceName);
                Ast.ModuleDecl source = expandModule(resolved);
                sourceSignature = signatures.get(source.signature);
            }
            if (sourceSignature == null) {
                throw new RuntimeException(owner + " includes non-module " + sourceName);
            }

            for (String member : sourceSignature.abstractTypes) {
                // Share the original opaque identity; never silently copy a
                // representation such as int into a new abstract type owner.
                String shared = sourceName + "." + member;
                String previous = types.putIfAbsent(member, shared);
                if (previous != null && !previous.equals(shared)) {
                    throw new RuntimeException(owner + " has conflicting included abstract " +
                            "type " + member + ": " + previous + " vs " + shared);
                }
            }
            for (Ast.MethodSig method : sourceSignature.methods) {
                Ast.MethodDecl delegate = delegate(sourceName, method);
                Ast.MethodDecl previous = methods.putIfAbsent(method.name, delegate);
                if (previous != null && !compatible(previous, delegate)) {
                    throw new RuntimeException(owner + " has incompatible included export " +
                            method.name);
                }
            }
        }
        for (Map.Entry<String, String> entry : localTypes.entrySet()) {
            String already = types.put(entry.getKey(), entry.getValue());
            if (already != null && !already.equals(entry.getValue())) {
                throw new RuntimeException(owner + " must preserve included opaque type " +
                        entry.getKey() + " = " + already);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Ast.MethodDecl method : localMethods) {
            if (!seen.add(method.name)) {
                throw new RuntimeException(owner + " defines duplicate export " + method.name);
            }
            // Explicit local declarations override included exports.
            methods.put(method.name, method);
        }
        return new ResolvedMembers(new ArrayList<>(methods.values()), types);
    }

    private static boolean compatible(Ast.MethodDecl a, Ast.MethodDecl b) {
        return a.params.size() == b.params.size() &&
                a.paramTypes.equals(b.paramTypes) &&
                Objects.equals(a.returnType, b.returnType);
    }

    private static Ast.MethodDecl delegate(String owner, Ast.MethodSig method) {
        Ast.Expr target = null;
        for (String part : owner.split("\\.")) {
            target = target == null ? new Ast.Var(part) : new Ast.FieldAccess(target, part);
        }
        List<Ast.Expr> args = new ArrayList<>();
        for (String name : method.params) args.add(new Ast.Var(name));
        Ast.MethodCall call = new Ast.MethodCall(target, method.name, args);
        return new Ast.MethodDecl(method.name, false, method.params,
                method.paramTypes, method.returnType,
                List.of(new Ast.ReturnStmt(call)), method.span);
    }
}
