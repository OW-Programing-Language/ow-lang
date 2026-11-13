package dev.oreo;

import java.util.*;

/**
 * Flatten lexical module declarations to qualified implementation paths.
 *
 * The source tree retains actual nested declarations. Normalization resolves
 * names against lexical scopes before existing module checking/interpreter
 * paths see them. Children are attached to their owner (not a global module
 * namespace) and are accessible externally only if the owner signature
 * exports the nested module member.
 */
public final class LexicalModuleNormalizer {
    private final List<Ast.ModuleDecl> output = new ArrayList<>();
    private final List<Ast.ModuleTypeDecl> signatures = new ArrayList<>();

    private LexicalModuleNormalizer(Ast.Program program) {
        signatures.addAll(program.moduleTypes);
    }

    public static Ast.Program normalize(Ast.Program program) {
        boolean hasNested = program.modules.stream().anyMatch(mod ->
                !mod.nestedModules.isEmpty() || !mod.nestedSignatures.isEmpty());
        if (!hasNested) return program;
        LexicalModuleNormalizer normalizer = new LexicalModuleNormalizer(program);
        for (Ast.ModuleDecl mod : program.modules)
            normalizer.append(mod, "", Map.of());
        return new Ast.Program(program.imports, program.functions,
                program.enums, program.interfaces, program.classes,
                program.models, normalizer.signatures, normalizer.output,
                program.functors, program.functorTypes, program.moduleAliases,
                program.mainBody);
    }

    private void append(Ast.ModuleDecl mod, String owner,
                        Map<String, String> inheritedScope) {
        if (!owner.isEmpty() && mod.name.contains(".")) {
            throw new RuntimeException("Lexical nested module names must be simple: " + mod.name);
        }
        String qualified = owner.isEmpty() ? mod.name : owner + "." + mod.name;
        Map<String, String> scope = new LinkedHashMap<>(inheritedScope);
        Set<String> newNames = new HashSet<>();
        for (Ast.ModuleTypeDecl nested : mod.nestedSignatures) {
            if (nested.name.contains(".") || !newNames.add(nested.name)) {
                throw new RuntimeException("Duplicate lexical module_type " +
                        qualified + "." + nested.name);
            }
            scope.put(nested.name, qualified + "." + nested.name);
        }
        for (Ast.ModuleTypeDecl nested : mod.nestedSignatures) {
            List<String> included = new ArrayList<>();
            for (String include : nested.includes)
                included.add(scope.getOrDefault(include, include));
            Map<String, String> required = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : nested.nestedModules.entrySet())
                required.put(e.getKey(), scope.getOrDefault(e.getValue(), e.getValue()));
            signatures.add(new Ast.ModuleTypeDecl(scope.get(nested.name),
                    nested.methods, nested.abstractTypes, nested.manifestTypes,
                    included, required, nested.span));
        }
        Map<String, String> children = new LinkedHashMap<>();
        for (Ast.ModuleDecl child : mod.nestedModules) {
            if (child.name.contains(".") || children.putIfAbsent(child.name,
                    qualified + "." + child.name) != null) {
                throw new RuntimeException("Duplicate lexical child module " +
                        qualified + "." + child.name);
            }
        }
        String signature = scope.getOrDefault(mod.signature, mod.signature);
        List<String> includes = new ArrayList<>();
        for (String include : mod.includes)
            includes.add(scope.getOrDefault(include, include));

        output.add(new Ast.ModuleDecl(qualified, signature, mod.methods,
                mod.typeAliases, mod.typeSharing, includes,
                List.of(), List.of(), children, mod.span));
        for (Ast.ModuleDecl child : mod.nestedModules)
            append(child, qualified, scope);
    }
}
