package dev.oreo;

import java.util.*;

/**
 * Conservative pre-main semantic analysis for statically known module paths.
 *
 * Rejects incompatible abstract type identities, primitive argument mismatches
 * and opaque type escapes before executing main. Unknown/dynamic expressions
 * remain checked by the interpreter at runtime.
 */
public final class StaticModuleValueChecker {
    private final Map<String, Ast.ModuleTypeDecl> signatures;
    private final Map<String, Ast.FunctorDecl> functors = new HashMap<>();
    private final Map<String, Ast.FunctorDecl> functorValues = new HashMap<>();
    private final Map<String, Ast.FunctorTypeDecl> functorTypes = new HashMap<>();
    private final Map<String, ModuleRef> moduleValues = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> lexicalOwners = new HashMap<>();
    private final Map<String, PackedRef> packedValues = new HashMap<>();
    private final Map<String, ValueType> valueTypes = new HashMap<>();
    private long freshCounter = 0;

    private record ModuleRef(String signature, String identity,
                             Map<String, String> aliases, Map<String, ModuleRef> captured) {}
    private record PackedRef(ModuleRef original, String exposedSignature) {}
    private record ValueType(String primitive, String abstractIdentity) {
        static ValueType primitive(String name) { return new ValueType(name, null); }
        static ValueType opaque(String identity) { return new ValueType(null, identity); }
    }

    private StaticModuleValueChecker(Map<String, Ast.ModuleTypeDecl> signatures) {
        this.signatures = signatures;
    }

    public static void check(Ast.Program program, Map<String, Ast.ModuleTypeDecl> signatures) {
        StaticModuleValueChecker checker = new StaticModuleValueChecker(signatures);
        for (Ast.ModuleDecl module : program.modules) {
            checker.lexicalOwners.put(module.name, module);
            checker.moduleValues.put(module.name,
                    new ModuleRef(module.signature, module.name, module.typeAliases, Map.of()));
        }
        for (Map.Entry<String, String> alias :
                ModuleTypeChecker.resolvedAliases(program).entrySet()) {
            ModuleRef original = checker.moduleValues.get(alias.getValue());
            if (original != null) checker.moduleValues.put(alias.getKey(), original);
        }
        checker.functorTypes.putAll(ModuleTypeChecker.functorSignatures(program));
        for (Ast.FunctorDecl functor : program.functors) {
            checker.functors.put(functor.name, functor);
            checker.functorValues.put(functor.name, functor);
        }
        checker.analyze(program.mainBody);
    }

    private void analyze(List<Ast.Stmt> body) {
        if (body == null) return;
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.SetStmt set) {
                PackedRef packed = inferPacked(set.value);
                if (packed != null) {
                    packedValues.put(set.name, packed);
                    moduleValues.remove(set.name);
                    valueTypes.remove(set.name);
                    functorValues.remove(set.name);
                    continue;
                }
                packedValues.remove(set.name);
                Ast.FunctorDecl returnedFunctor = inferFunctor(set.value);
                if (returnedFunctor != null) {
                    if (set.typeName != null && functorTypes.containsKey(set.typeName) &&
                        !ModuleTypeChecker.matchesFunctor(returnedFunctor,
                                functorTypes.get(set.typeName), signatures)) {
                        throw error(set.span, "functor value " + returnedFunctor.name +
                                " does not satisfy " + set.typeName);
                    }
                    functorValues.put(set.name, returnedFunctor);
                    moduleValues.remove(set.name);
                    valueTypes.remove(set.name);
                    continue;
                }
                functorValues.remove(set.name);
                ModuleRef module = inferModule(set.value);
                if (module != null) {
                    moduleValues.put(set.name, module);
                    valueTypes.remove(set.name);
                } else {
                    ValueType type = inferValue(set.value);
                    if (set.typeName != null && type != null &&
                            !signatures.containsKey(set.typeName)) {
                        ensureAssignable(set.typeName, type, set.span);
                    }
                    if (type != null) valueTypes.put(set.name, type);
                    else valueTypes.remove(set.name);
                    moduleValues.remove(set.name);
                }
            } else if (stmt instanceof Ast.PrintStmt print) {
                inferValue(print.value);
            } else if (stmt instanceof Ast.CallStmt call) {
                inferValue(call.call);
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                inferValue(ret.value);
            } else if (stmt instanceof Ast.IfStmt branch) {
                inferValue(branch.condition);
                if (branch.condition instanceof Ast.BoolLit literal) {
                    // A literal branch is definitely taken (or unreachable).
                    analyze(literal.value ? branch.thenBody : branch.elseBody);
                } else {
                    joinBranches(Arrays.asList(branch.thenBody, branch.elseBody));
                }
            } else if (stmt instanceof Ast.WhileStmt loop) {
                inferValue(loop.condition);
                if (!(loop.condition instanceof Ast.BoolLit literal && !literal.value)) {
                    // A loop may run zero or more times: its assignments are
                    // only trusted if identical to the incoming environment.
                    joinBranches(Arrays.asList(null, loop.body));
                }
            } else if (stmt instanceof Ast.TryStmt tr) {
                joinBranches(Arrays.asList(tr.tryBody, tr.catchBody));
            } else if (stmt instanceof Ast.SwitchStmt sw) {
                inferValue(sw.subject);
                List<List<Ast.Stmt>> alternatives = new ArrayList<>();
                for (Ast.CaseClause clause : sw.cases) {
                    for (Ast.Expr expr : clause.values) inferValue(expr);
                    alternatives.add(clause.body);
                }
                alternatives.add(sw.defaultBody);
                joinBranches(alternatives);
            } else if (stmt instanceof Ast.SetFieldStmt set) {
                inferValue(set.target);
                inferValue(set.value);
            } else if (stmt instanceof Ast.SetIndexStmt set) {
                inferValue(set.index);
                inferValue(set.value);
            } else if (stmt instanceof Ast.SaveStmt save) {
                inferValue(save.record);
            } else if (stmt instanceof Ast.DeleteStmt del) {
                inferValue(del.where);
            } else if (stmt instanceof Ast.RespondStmt response) {
                inferValue(response.value);
            } else if (stmt instanceof Ast.ServeStmt serve) {
                inferValue(serve.port);
            } else if (stmt instanceof Ast.RouteStmt route) {
                branchOf(route.body);
            }
        }
    }

    private record FlowState(Map<String, ModuleRef> modules,
                             Map<String, PackedRef> packages,
                             Map<String, ValueType> values,
                             Map<String, Ast.FunctorDecl> functorValues) {}

    private FlowState snapshot() {
        return new FlowState(new HashMap<>(moduleValues),
                             new HashMap<>(packedValues),
                             new HashMap<>(valueTypes),
                             new HashMap<>(functorValues));
    }

    private void restore(FlowState state) {
        moduleValues.clear();
        moduleValues.putAll(state.modules);
        packedValues.clear();
        packedValues.putAll(state.packages);
        valueTypes.clear();
        valueTypes.putAll(state.values);
        functorValues.clear();
        functorValues.putAll(state.functorValues);
    }

    /** Keep only values proved identical on every possible control-flow path. */
    private static <T> Map<String, T> commonValues(List<Map<String, T>> alternatives) {
        Map<String, T> retained = new HashMap<>(alternatives.get(0));
        for (int i = 1; i < alternatives.size(); i++) {
            Map<String, T> next = alternatives.get(i);
            retained.entrySet().removeIf(entry ->
                    !next.containsKey(entry.getKey()) ||
                    !Objects.equals(entry.getValue(), next.get(entry.getKey())));
        }
        return retained;
    }

    private void joinBranches(List<List<Ast.Stmt>> alternatives) {
        FlowState initial = snapshot();
        List<FlowState> outcomes = new ArrayList<>();
        for (List<Ast.Stmt> body : alternatives) {
            restore(initial);
            analyze(body);
            outcomes.add(snapshot());
        }
        restore(new FlowState(
                commonValues(outcomes.stream().map(FlowState::modules).toList()),
                commonValues(outcomes.stream().map(FlowState::packages).toList()),
                commonValues(outcomes.stream().map(FlowState::values).toList()),
                commonValues(outcomes.stream().map(FlowState::functorValues).toList())));
    }

    /** Branch-local bindings cannot leak into the parent, but are still checked. */
    private void branchOf(List<Ast.Stmt> body) {
        Map<String, ModuleRef> savedModules = new HashMap<>(moduleValues);
        Map<String, PackedRef> savedPackages = new HashMap<>(packedValues);
        Map<String, ValueType> savedValues = new HashMap<>(valueTypes);
        Map<String, Ast.FunctorDecl> savedFunctors = new HashMap<>(functorValues);
        analyze(body);
        moduleValues.clear();
        moduleValues.putAll(savedModules);
        packedValues.clear();
        packedValues.putAll(savedPackages);
        valueTypes.clear();
        valueTypes.putAll(savedValues);
        functorValues.clear();
        functorValues.putAll(savedFunctors);
    }

    private ModuleRef inferModule(Ast.Expr expr) {
        if (expr instanceof Ast.Var variable) return moduleValues.get(variable.name);
        if (expr instanceof Ast.FieldAccess field) {
            String path = modulePath(field);
            if (path != null) requireVisiblePath(path);
            return path == null ? null : moduleValues.get(path);
        }
        if (!(expr instanceof Ast.CallExpr call)) return null;
        if ("unpack".equals(call.name)) {
            if (call.args.size() != 2) throw error(call.span, "unpack expects 2 arguments");
            PackedRef packed = inferPacked(call.args.get(0));
            String requested = literalSignature(call, 1);
            if (packed == null) return null; // dynamic storage is checked by interpreter
            if (!ModuleTypeChecker.matches(signatures.get(packed.exposedSignature),
                                            signatures.get(requested))) {
                throw error(call.span, "unpack cannot widen sealed module from " +
                        packed.exposedSignature + " to " + requested);
            }
            ModuleRef original = packed.original;
            return new ModuleRef(requested, original.identity, original.aliases, original.captured);
        }
        Ast.FunctorDecl functor = functorValues.get(call.name);
        if (functor == null || call.args.size() != 1 || functor.returnedFunctor != null) {
            return null;
        }
        ModuleRef argument = inferModule(call.args.get(0));
        if (argument == null || !signatures.containsKey(functor.inputSignature)) return null;
        if (!ModuleTypeChecker.matches(signatures.get(argument.signature),
                                        signatures.get(functor.inputSignature))) {
            throw error(call.span, "Functor " + functor.name + " requires " +
                    functor.inputSignature + ", got " + argument.signature);
        }
        String identity = functor.generative
                ? functor.name + "#static" + (++freshCounter)
                : functor.name + "(" + argument.identity + ")";
        return new ModuleRef(functor.outputSignature, identity, functor.typeAliases,
                Map.of(functor.argument, argument));
    }

    /** Resolve statically known higher-order functor results without execution. */
    private Ast.FunctorDecl inferFunctor(Ast.Expr expr) {
        if (expr instanceof Ast.Var var) return functorValues.get(var.name);
        if (!(expr instanceof Ast.CallExpr call)) return null;
        Ast.FunctorDecl maker = functorValues.get(call.name);
        if (maker == null || maker.returnedFunctor == null) return null;
        if (call.args.size() != 1)
            throw error(call.span, "Functor " + maker.name + " expects one argument");
        if (maker.returnedFunctor.equals(maker.argument)) {
            Ast.FunctorDecl supplied = inferFunctor(call.args.get(0));
            if (supplied == null) return null;
            Ast.FunctorTypeDecl required = functorTypes.get(maker.inputSignature);
            if (required != null &&
                !ModuleTypeChecker.matchesFunctor(supplied, required, signatures)) {
                throw error(call.span, "Functor " + maker.name +
                        " requires functor " + maker.inputSignature +
                        ", got " + supplied.name);
            }
            return supplied;
        }
        return functors.get(maker.returnedFunctor);
    }

    private String literalSignature(Ast.CallExpr call, int index) {
        if (call.args.size() <= index ||
                !(call.args.get(index) instanceof Ast.StringLit requested)) {
            throw error(call.span, call.name + " requires a literal module signature name");
        }
        if (!signatures.containsKey(requested.value)) {
            throw error(call.span, "Unknown module signature " + requested.value);
        }
        return requested.value;
    }

    private PackedRef inferPacked(Ast.Expr expr) {
        if (expr instanceof Ast.Var variable) return packedValues.get(variable.name);
        if (!(expr instanceof Ast.CallExpr call) || !"pack".equals(call.name)) return null;
        if (call.args.size() != 2) throw error(call.span, "pack expects 2 arguments");
        String requested = literalSignature(call, 1);
        ModuleRef original = inferModule(call.args.get(0));
        if (original == null) return null; // dynamic source rejected by runtime
        if (!ModuleTypeChecker.matches(signatures.get(original.signature),
                                       signatures.get(requested))) {
            throw error(call.span, "pack cannot ascribe " + original.signature +
                    " as " + requested);
        }
        return new PackedRef(original, requested);
    }

    private void requireVisiblePath(String path) {
        int dot = path.indexOf('.');
        while (dot >= 0) {
            String parentName = path.substring(0, dot);
            int next = path.indexOf('.', dot + 1);
            String member = next < 0
                    ? path.substring(dot + 1) : path.substring(dot + 1, next);
            Ast.ModuleDecl owner = lexicalOwners.get(parentName);
            if (owner != null && owner.lexicalChildren.containsKey(member)) {
                Ast.ModuleTypeDecl contract = signatures.get(owner.signature);
                if (contract == null || !contract.nestedModules.containsKey(member)) {
                    throw new RuntimeException("Private nested module " + parentName +
                            "." + member + " is not exported");
                }
            }
            dot = next;
        }
    }

    private static String modulePath(Ast.Expr expr) {
        if (expr instanceof Ast.Var variable) return variable.name;
        if (expr instanceof Ast.FieldAccess field) {
            String parent = modulePath(field.target);
            return parent == null ? null : parent + "." + field.field;
        }
        return null;
    }

    private ValueType typeMember(ModuleRef module, String member) {
        Ast.ModuleTypeDecl declaration = signatures.get(module.signature);
        if (declaration == null) return null;
        String manifest = declaration.manifestTypes.get(member);
        if (manifest != null) {
            if (!manifest.contains(".")) return ValueType.primitive(manifest);
            String[] path = manifest.split("\\.", 2);
            ModuleRef declared = module.captured.containsKey(path[0])
                    ? module.captured.get(path[0]) : moduleValues.get(path[0]);
            return declared == null ? null : typeMember(declared, path[1]);
        }
        String alias = module.aliases.get(member);
        if (alias == null) return null;
        if (alias.contains(".")) {
            String[] path = alias.split("\\.", 2);
            ModuleRef dependency = module.captured.containsKey(path[0])
                    ? module.captured.get(path[0]) : moduleValues.get(path[0]);
            return dependency == null ? null : typeMember(dependency, path[1]);
        }
        return ValueType.opaque(module.identity + "." + member);
    }

    private ValueType inferValue(Ast.Expr expr) {
        if (expr == null) return null;
        if (expr instanceof Ast.IntLit) return ValueType.primitive("int");
        if (expr instanceof Ast.FloatLit) return ValueType.primitive("float");
        if (expr instanceof Ast.StringLit) return ValueType.primitive("string");
        if (expr instanceof Ast.BoolLit) return ValueType.primitive("bool");
        if (expr instanceof Ast.Var var) return valueTypes.get(var.name);
        if (expr instanceof Ast.MethodCall call) {
            ModuleRef target = inferModule(call.target);
            if (target != null) {
                Ast.ModuleTypeDecl declaration = signatures.get(target.signature);
                if (declaration == null) return null;
                Ast.MethodSig method = declaration.methods.stream()
                        .filter(m -> m.name.equals(call.method)).findFirst().orElse(null);
                if (method == null) return null; // already covered by ModuleTypeChecker
                for (int i = 0; i < call.args.size(); i++) {
                    ValueType passed = inferValue(call.args.get(i));
                    if (passed == null || i >= method.paramTypes.size()) continue;
                    String required = method.paramTypes.get(i);
                    if (required == null) continue;
                    if (declaration.abstractTypes.contains(required)) {
                        ValueType expected = typeMember(target, required);
                        if (expected != null) {
                            if (expected.primitive != null && passed.abstractIdentity != null) {
                                throw error(call.span, "manifest type " + declaration.name +
                                        "." + required + " expects " + expected.primitive +
                                        "; opaque type " + passed.abstractIdentity +
                                        " cannot be used as " + expected.primitive);
                            }
                            ensureSame(expected, passed, call.span);
                        }
                    } else {
                        ensureAssignable(required, passed, call.span);
                    }
                }
                if (method.returnType == null) return null;
                if (declaration.abstractTypes.contains(method.returnType)) {
                    return typeMember(target, method.returnType);
                }
                return ValueType.primitive(method.returnType);
            }
            inferValue(call.target);
            for (Ast.Expr arg : call.args) inferValue(arg);
            return null;
        }
        if (expr instanceof Ast.CallExpr call) {
            if ("pack".equals(call.name)) {
                inferPacked(call);
                return null;
            }
            if ("unpack".equals(call.name)) {
                inferModule(call);
                return null;
            }
            for (Ast.Expr arg : call.args) inferValue(arg);
            return null;
        }
        if (expr instanceof Ast.BinOp operation) {
            ValueType left = inferValue(operation.left);
            ValueType right = inferValue(operation.right);
            if ((left != null && left.abstractIdentity != null) ||
                (right != null && right.abstractIdentity != null)) {
                throw error(operation.span, "opaque abstract type escapes through arithmetic");
            }
            if ("+".equals(operation.op) &&
                (isString(left) || isString(right))) return ValueType.primitive("string");
            if (left != null && "float".equals(left.primitive)) return left;
            if (right != null && "float".equals(right.primitive)) return right;
            return ValueType.primitive("int");
        }
        if (expr instanceof Ast.Compare operation) {
            inferValue(operation.left);
            inferValue(operation.right);
            return ValueType.primitive("bool");
        }
        if (expr instanceof Ast.ArrayLit array) {
            for (Ast.Expr value : array.elements) inferValue(value);
            return ValueType.primitive("array");
        }
        if (expr instanceof Ast.MapLit map) {
            for (Ast.Expr value : map.values) inferValue(value);
            return null;
        }
        if (expr instanceof Ast.IndexExpr idx) {
            inferValue(idx.target);
            inferValue(idx.index);
            return null;
        }
        if (expr instanceof Ast.FieldAccess field) {
            inferValue(field.target);
            return null;
        }
        if (expr instanceof Ast.NewExpr call) {
            for (Ast.Expr arg : call.args) inferValue(arg);
            return null;
        }
        if (expr instanceof Ast.FindExpr find) {
            inferValue(find.where);
            return null;
        }
        return null;
    }

    private static boolean isString(ValueType type) {
        return type != null && "string".equals(type.primitive);
    }

    private static void ensureAssignable(String required, ValueType provided, Ast.SourceSpan span) {
        if (provided == null) return;
        if (provided.abstractIdentity != null) {
            throw error(span, "opaque type " + provided.abstractIdentity +
                    " cannot be used as " + required);
        }
        String actual = provided.primitive;
        if (required.equals(actual) ||
                (required.equals("float") || required.equals("double")) &&
                  (actual.equals("float") || actual.equals("int")) ||
                (required.equals("bool") || required.equals("boolean")) &&
                  (actual.equals("bool") || actual.equals("int")) ||
                (required.equals("str") && actual.equals("string"))) {
            return;
        }
        // Only reject provably incompatible primitive types; custom OW types
        // are still handled by the interpreter.
        if (Set.of("int", "float", "double", "string", "str", "bool", "boolean", "array").contains(required)) {
            throw error(span, "expected " + required + " but got " + actual);
        }
    }

    private static void ensureSame(ValueType expected, ValueType actual, Ast.SourceSpan span) {
        if (expected.abstractIdentity != null) {
            if (!expected.abstractIdentity.equals(actual.abstractIdentity)) {
                throw error(span, "Abstract type mismatch (static abstract type mismatch): expected " +
                        expected.abstractIdentity + ", got " +
                        (actual.abstractIdentity != null ? actual.abstractIdentity : actual.primitive));
            }
        } else if (expected.primitive != null) {
            ensureAssignable(expected.primitive, actual, span);
        }
    }

    private static RuntimeException error(Ast.SourceSpan span, String message) {
        String place = span != null && span.line > 0
                ? " at line " + span.line + ", column " + span.column +
                  " (" + span.location() + ")"
                : "";
        return new RuntimeException("Module type error" + place + ": " + message);
    }
}
