package dev.oreo;

import java.util.*;

/**
 * A deliberately bounded AOT monomorphization for the existing i32 LLVM backend.
 *
 * Statically named modules and functor applications are reduced to ordinary OW
 * functions. It does NOT erase arbitrary first-class packages, dynamic module
 * dispatch, abstract type identities or non-integer exports: those constructs
 * are rejected rather than silently emitted incorrectly.
 */
public final class NativeModuleLlvmLowerer {
    private final Ast.Program source;
    private final Map<String, Ast.ModuleTypeDecl> signatures;
    private final Map<String, Ast.FunctorDecl> functors = new LinkedHashMap<>();
    private final Map<String, Instance> globalModules = new LinkedHashMap<>();
    private final Map<String, Instance> applicativeInstances = new LinkedHashMap<>();
    private final Map<String, Ast.FnDecl> generatedFunctions = new LinkedHashMap<>();
    private final Map<String, String> generatedSymbols = new LinkedHashMap<>();
    private int serial;

    private record Instance(String id, String signature,
                            Map<String, Ast.MethodDecl> exports,
                            Map<String, String> typeAliases,
                            Map<String, Instance> captured) {}
    private record Packed(Instance original, String publicSignature) {}

    private NativeModuleLlvmLowerer(Ast.Program program) {
        source = ModuleImplementationExpander.expand(program);
        signatures = ModuleTypeChecker.signatures(source);
        for (Ast.FunctorDecl functor : source.functors) functors.put(functor.name, functor);

        for (Ast.ModuleDecl module : source.modules) {
            Map<String, Ast.MethodDecl> methods = new LinkedHashMap<>();
            for (Ast.MethodDecl method : module.methods) methods.put(method.name, method);
            globalModules.put(module.name,
                    new Instance(module.name, module.signature, methods,
                                 module.typeAliases, Map.of()));
        }
        Map<String, String> aliases = ModuleTypeChecker.resolvedAliases(source);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            globalModules.put(alias.getKey(), globalModules.get(alias.getValue()));
        }
    }

    public static Ast.Program lower(Ast.Program program) {
        if (program.moduleTypes.isEmpty() && program.modules.isEmpty() &&
            program.functors.isEmpty() && program.functorTypes.isEmpty() &&
            program.moduleAliases.isEmpty()) return program;
        return new NativeModuleLlvmLowerer(program).lower();
    }

    private Ast.Program lower() {
        if (!source.functorTypes.isEmpty()) {
            throw unsupported("higher-order functor values are not yet lowered");
        }
        if (!source.classes.isEmpty() || !source.models.isEmpty()) {
            throw unsupported("mixing native modules with classes/models is not supported by LLVM");
        }
        Map<String, Instance> mainModules = new LinkedHashMap<>(globalModules);
        List<Ast.Stmt> main = rewrite(source.mainBody, mainModules, new LinkedHashMap<>());

        List<Ast.FnDecl> functions = new ArrayList<>();
        for (Ast.FnDecl fn : source.functions) {
            Map<String, Instance> locals = new LinkedHashMap<>(globalModules);
            for (String param : fn.params) locals.remove(param);
            functions.add(new Ast.FnDecl(fn.name, fn.params,
                    rewrite(fn.body, locals, new LinkedHashMap<>())));
        }
        functions.addAll(generatedFunctions.values());
        return new Ast.Program(List.of(), functions, source.enums,
                source.interfaces, source.classes, source.models,
                List.of(), List.of(), List.of(), List.of(), List.of(), main);
    }

    private List<Ast.Stmt> rewrite(List<Ast.Stmt> body, Map<String, Instance> modules,
                                   Map<String, Packed> packages) {
        if (body == null) throw unsupported("missing main or function body");
        List<Ast.Stmt> result = new ArrayList<>();
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.SetStmt set) {
                Packed packed = inferPacked(set.value, modules, packages);
                if (packed != null) {
                    packages.put(set.name, packed);
                    modules.remove(set.name);
                    continue;
                }
                packages.remove(set.name);
                Instance module = inferModule(set.value, modules, packages);
                if (module != null) {
                    // Compile-time module binding produces no runtime i32 storage.
                    modules.put(set.name, module);
                    continue;
                }
                modules.remove(set.name);
                result.add(new Ast.SetStmt(set.name, set.typeName,
                        rewriteExpr(set.value, modules, packages), set.span));
            } else if (stmt instanceof Ast.PrintStmt print) {
                result.add(new Ast.PrintStmt(rewriteExpr(print.value, modules, packages)));
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                result.add(new Ast.ReturnStmt(rewriteExpr(ret.value, modules, packages)));
            } else if (stmt instanceof Ast.CallStmt call) {
                result.add(new Ast.CallStmt(rewriteExpr(call.call, modules, packages)));
            } else if (stmt instanceof Ast.IfStmt branch) {
                result.add(new Ast.IfStmt(rewriteExpr(branch.condition, modules, packages),
                        rewrite(branch.thenBody, new LinkedHashMap<>(modules),
                                new LinkedHashMap<>(packages)),
                        branch.elseBody == null ? null :
                            rewrite(branch.elseBody, new LinkedHashMap<>(modules),
                                    new LinkedHashMap<>(packages))));
            } else if (stmt instanceof Ast.WhileStmt loop) {
                result.add(new Ast.WhileStmt(rewriteExpr(loop.condition, modules, packages),
                        rewrite(loop.body, new LinkedHashMap<>(modules),
                                new LinkedHashMap<>(packages))));
            } else {
                throw unsupported("unsupported statement " + stmt.getClass().getSimpleName());
            }
        }
        return result;
    }

    private Ast.Expr rewriteExpr(Ast.Expr expr, Map<String, Instance> modules,
                                 Map<String, Packed> packages) {
        if (expr instanceof Ast.MethodCall call) {
            Instance module = inferModule(call.target, modules, packages);
            if (module == null) throw unsupported("dynamic method dispatch " + call.method);
            List<Ast.Expr> args = new ArrayList<>();
            for (Ast.Expr arg : call.args) args.add(rewriteExpr(arg, modules, packages));
            return new Ast.CallExpr(materialize(module, call.method), args, call.span);
        }
        if (expr instanceof Ast.CallExpr call) {
            if ("pack".equals(call.name) || "unpack".equals(call.name))
                throw unsupported("first-class module package values need a native runtime");
            if (functors.containsKey(call.name))
                throw unsupported("a module functor result cannot be used as an i32 value");
            List<Ast.Expr> args = new ArrayList<>();
            for (Ast.Expr arg : call.args) args.add(rewriteExpr(arg, modules, packages));
            return new Ast.CallExpr(call.name, args, call.span);
        }
        if (expr instanceof Ast.Var var && modules.containsKey(var.name))
            throw unsupported("a native module cannot be stored as an i32 value: " + var.name);
        if (expr instanceof Ast.Var var && packages.containsKey(var.name))
            throw unsupported("a packed module cannot be stored as an i32 value: " + var.name);
        if (expr instanceof Ast.FieldAccess field)
            throw unsupported("a module namespace/record cannot be an i32 expression");
        if (expr instanceof Ast.BinOp bin)
            return new Ast.BinOp(bin.op, rewriteExpr(bin.left, modules, packages),
                    rewriteExpr(bin.right, modules, packages), bin.span);
        if (expr instanceof Ast.Compare cmp)
            return new Ast.Compare(cmp.op, rewriteExpr(cmp.left, modules, packages),
                    rewriteExpr(cmp.right, modules, packages));
        if (expr instanceof Ast.IntLit || expr instanceof Ast.StringLit ||
                expr instanceof Ast.BoolLit || expr instanceof Ast.Var) return expr;
        throw unsupported("only i32/static module expressions are currently lowered, got " +
                expr.getClass().getSimpleName());
    }

    private String materialize(Instance module, String name) {
        Ast.ModuleTypeDecl signature = signatures.get(module.signature);
        if (signature == null)
            throw unsupported("unknown module signature for " + module.id);
        Ast.MethodSig exposed = signature.methods.stream()
                .filter(method -> method.name.equals(name)).findFirst().orElse(null);
        if (exposed == null)
            throw unsupported("method " + module.id + "." + name + " is not public");
        if (!i32Compatible(module, exposed.returnType) ||
            exposed.paramTypes.stream().anyMatch(t -> !i32Compatible(module, t)))
            throw unsupported("only i32-representable module signatures are supported: " +
                              module.id + "." + name);
        Ast.MethodDecl impl = module.exports.get(name);
        if (impl == null) throw unsupported("module method missing " + module.id + "." + name);

        String key = module.id + "::" + name;
        String symbol = generatedSymbols.get(key);
        if (symbol != null) return symbol;
        symbol = "__ow_module_" + (serial++) + "_" +
                module.id.replaceAll("[^A-Za-z0-9_]", "_") + "_" + name;
        generatedSymbols.put(key, symbol);
        // Insert placeholder first so recursive method calls terminate.
        generatedFunctions.put(key, new Ast.FnDecl(symbol, impl.params, List.of()));
        Map<String, Instance> captured = new LinkedHashMap<>(globalModules);
        captured.putAll(module.captured);
        for (String param : impl.params) captured.remove(param);
        Ast.FnDecl compiled = new Ast.FnDecl(symbol, impl.params,
                rewrite(impl.body, captured, new LinkedHashMap<>()));
        generatedFunctions.put(key, compiled);
        return symbol;
    }

    private Instance inferModule(Ast.Expr expr, Map<String, Instance> modules,
                                 Map<String, Packed> packages) {
        String path = staticPath(expr);
        if (path != null) return modules.get(path);
        if (!(expr instanceof Ast.CallExpr call)) return null;
        if ("unpack".equals(call.name)) {
            if (call.args.size() != 2)
                throw unsupported("unpack requires a package and its public signature");
            Packed packageValue = inferPacked(call.args.get(0), modules, packages);
            if (packageValue == null)
                throw unsupported("dynamic first-class module package cannot be lowered");
            String requested = literalModuleSignature(call.args.get(1));
            if (!ModuleTypeChecker.matches(signatures.get(packageValue.publicSignature),
                                           signatures.get(requested)))
                throw unsupported("cannot widen sealed module package " +
                        packageValue.publicSignature + " to " + requested);
            Instance original = packageValue.original;
            return new Instance(original.id, requested, original.exports,
                                original.typeAliases, original.captured);
        }
        if (!functors.containsKey(call.name)) return null;
        Ast.FunctorDecl functor = functors.get(call.name);
        if (call.args.size() != 1 || functor.returnedFunctor != null)
            throw unsupported("higher-order and multi-argument functors are not lowered");
        Instance argument = inferModule(call.args.get(0), modules, packages);
        if (argument == null)
            throw unsupported("functor " + functor.name + " requires a static module argument");
        if (!ModuleTypeChecker.matches(signatures.get(argument.signature),
                signatures.get(functor.inputSignature)))
            throw unsupported("functor " + functor.name + " input signature mismatch");
        String key = functor.name + "(" + argument.id + ")";
        if (!functor.generative && applicativeInstances.containsKey(key))
            return applicativeInstances.get(key);
        Map<String, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : functor.methods) methods.put(method.name, method);
        String id = functor.generative ? key + "#" + (++serial) : key;
        Instance instance = new Instance(id, functor.outputSignature, methods,
                functor.typeAliases, Map.of(functor.argument, argument));
        if (!functor.generative) applicativeInstances.put(key, instance);
        return instance;
    }

    private String literalModuleSignature(Ast.Expr expression) {
        if (!(expression instanceof Ast.StringLit literal) ||
                !signatures.containsKey(literal.value))
            throw unsupported("module package signature must be a known string literal");
        return literal.value;
    }

    /** A package with a fully known source and ascription can be erased at AOT. */
    private Packed inferPacked(Ast.Expr expr, Map<String, Instance> modules,
                               Map<String, Packed> packages) {
        if (expr instanceof Ast.Var var) return packages.get(var.name);
        if (!(expr instanceof Ast.CallExpr call) || !"pack".equals(call.name))
            return null;
        if (call.args.size() != 2)
            throw unsupported("pack requires a module and its public signature");
        Instance sourceModule = inferModule(call.args.get(0), modules, packages);
        if (sourceModule == null)
            throw unsupported("dynamic first-class module cannot be lowered");
        String exposed = literalModuleSignature(call.args.get(1));
        if (!ModuleTypeChecker.matches(signatures.get(sourceModule.signature),
                                       signatures.get(exposed)))
            throw unsupported("cannot pack " + sourceModule.signature + " as " + exposed);
        return new Packed(sourceModule, exposed);
    }

    /**
     * Erase a proven abstract type to i32 for this *statically monomorphized*
     * instance. The semantic checker runs before lowering and retains nominal
     * type identities, so only the representation disappears in generated IR.
     * Dynamic packaging/dispatch is still explicitly unsupported.
     */
    private boolean i32Compatible(Instance instance, String type) {
        return resolveRepresentation(instance, type, new LinkedHashSet<>()).equals("int");
    }

    private String resolveRepresentation(Instance instance, String type,
                                         Set<String> active) {
        if (type == null) return "<unknown>";
        if (!signatures.containsKey(instance.signature))
            throw unsupported("unknown signature " + instance.signature);
        if (!signatures.get(instance.signature).abstractTypes.contains(type)) return type;
        String key = instance.id + "." + type;
        if (!active.add(key))
            throw unsupported("cyclic abstract representation " + active + " -> " + key);
        String representation = instance.typeAliases.get(type);
        if (representation == null)
            throw unsupported("missing abstract representation " + key);
        if (representation.contains(".")) {
            String[] parts = representation.split("\\.", 2);
            Instance target = instance.captured.get(parts[0]);
            if (target == null) target = globalModules.get(parts[0]);
            if (target == null)
                throw unsupported("unresolved abstract type path " + representation);
            return resolveRepresentation(target, parts[1], active);
        }
        return representation;
    }

    private static String staticPath(Ast.Expr expr) {
        if (expr instanceof Ast.Var var) return var.name;
        if (expr instanceof Ast.FieldAccess field) {
            String target = staticPath(field.target);
            return target == null ? null : target + "." + field.field;
        }
        return null;
    }

    private static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Native LLVM static-module lowering: " + reason +
                ". Use 'ow run' for dynamic module values.");
    }
}
