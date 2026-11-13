package dev.oreo;

import java.util.*;

/**
 * Declaration-time checker for OW's native module signatures.
 *
 * This checks nominal names for declaration lookup, structural width of module
 * exports, public visibility, exact parameter arity and explicitly annotated
 * argument/return types. Checking occurs before any user `main:` statements.
 *
 * This is intentionally not a complete OCaml module type system: it currently
 * has no abstract type members, sealing of types, type equality constraints,
 * applicative/generative identities, or higher-order functor types.
 */
public final class ModuleTypeChecker {
    private ModuleTypeChecker() {}

    public static Map<String, Ast.ModuleTypeDecl> signatures(Ast.Program program) {
        Map<String, Ast.ModuleTypeDecl> signatures = new LinkedHashMap<>();
        for (Ast.ModuleTypeDecl sig : program.moduleTypes) {
            if (signatures.putIfAbsent(sig.name, sig) != null) {
                throw new RuntimeException("Duplicate module_type " + sig.name);
            }
            Set<String> memberTypes = new HashSet<>();
            for (String abstractType : sig.abstractTypes) {
                if (!memberTypes.add(abstractType)) {
                    throw new RuntimeException("Duplicate abstract type " + sig.name + "." + abstractType);
                }
            }
            Set<String> methods = new HashSet<>();
            for (Ast.MethodSig method : sig.methods) {
                if (!methods.add(method.name)) {
                    throw new RuntimeException("Duplicate export " + sig.name + "." + method.name);
                }
            }
        }
        return signatures;
    }

    public static Map<String, Ast.FunctorTypeDecl> functorSignatures(Ast.Program program) {
        Map<String, Ast.FunctorTypeDecl> result = new LinkedHashMap<>();
        Map<String, Ast.ModuleTypeDecl> modules = signatures(program);
        for (Ast.FunctorTypeDecl signature : program.functorTypes) {
            if (result.putIfAbsent(signature.name, signature) != null) {
                throw new RuntimeException("Duplicate functor_type " + signature.name);
            }
            if (!modules.containsKey(signature.inputSignature) ||
                !modules.containsKey(signature.outputSignature)) {
                throw new RuntimeException("functor_type " + signature.name +
                        " requires module signatures for input and output");
            }
        }
        return result;
    }

    /** Argument contravariance and result covariance under structural width. */
    public static boolean matchesFunctor(Ast.FunctorDecl actual, Ast.FunctorTypeDecl target,
                                         Map<String, Ast.ModuleTypeDecl> signatures) {
        return actual != null && target != null &&
               matches(signatures.get(target.inputSignature), signatures.get(actual.inputSignature)) &&
               matches(signatures.get(actual.outputSignature), signatures.get(target.outputSignature));
    }

    public static void check(Ast.Program program) {
        Map<String, Ast.ModuleTypeDecl> signatures = signatures(program);
        Map<String, Ast.FunctorTypeDecl> functorTypes = functorSignatures(program);
        Map<String, Ast.FunctorDecl> allFunctors = new HashMap<>();
        for (Ast.FunctorDecl declared : program.functors) {
            if (allFunctors.putIfAbsent(declared.name, declared) != null) {
                throw new RuntimeException("Duplicate functor declaration " + declared.name);
            }
        }
        Set<String> reserved = new HashSet<>();
        for (Ast.FnDecl fn : program.functions) reserved.add(fn.name);
        for (Ast.ClassDecl cls : program.classes) reserved.add(cls.name);
        for (Ast.InterfaceDecl iface : program.interfaces) reserved.add(iface.name);
        for (Ast.ModuleTypeDecl sig : program.moduleTypes) {
            if (!reserved.add(sig.name)) {
                throw new RuntimeException("Duplicate global declaration " + sig.name);
            }
        }
        for (Ast.FunctorTypeDecl type : program.functorTypes) {
            if (!reserved.add(type.name)) {
                throw new RuntimeException("Duplicate global declaration " + type.name);
            }
        }
        for (Ast.ModuleDecl mod : program.modules) {
            if (!reserved.add(mod.name)) {
                throw new RuntimeException("Duplicate global declaration " + mod.name);
            }
            validateMethods(mod.name, mod.signature, mod.methods, mod.typeAliases, signatures);
        }
        for (Ast.FunctorDecl functor : program.functors) {
            if (!reserved.add(functor.name)) {
                throw new RuntimeException("Duplicate global declaration " + functor.name);
            }
            if (!signatures.containsKey(functor.inputSignature) &&
                !functorTypes.containsKey(functor.inputSignature)) {
                throw new RuntimeException("Functor " + functor.name +
                        " references unknown input signature " + functor.inputSignature);
            }
            if (functor.argument.equals(functor.name)) {
                throw new RuntimeException("Functor " + functor.name + " shadows its own name");
            }
            Ast.ModuleTypeDecl input = signatures.get(functor.inputSignature);
            Ast.ModuleTypeDecl output = signatures.get(functor.outputSignature);
            for (Map.Entry<String, String> entry : functor.typeAliases.entrySet()) {
                String type = entry.getValue();
                if (!type.contains(".")) continue;
                validateDependentPath(functor, input, type);
            }
            for (Map.Entry<String, String> sharing : functor.typeSharing.entrySet()) {
                String member = sharing.getKey();
                if (output == null || !output.abstractTypes.contains(member)) {
                    throw new RuntimeException("Functor " + functor.name +
                            " constrains unknown output type " + member);
                }
                validateDependentPath(functor, input, sharing.getValue());
                if (!sharing.getValue().equals(functor.typeAliases.get(member))) {
                    throw new RuntimeException("Functor " + functor.name +
                            " must implement with_type " + member + " = " + sharing.getValue());
                }
                String inputMember = sharing.getValue().substring(sharing.getValue().indexOf('.') + 1);
                if (output.manifestTypes.containsKey(member) &&
                    !Objects.equals(output.manifestTypes.get(member), input.manifestTypes.get(inputMember))) {
                    throw new RuntimeException("Functor " + functor.name +
                            " has incompatible manifest type constraint " + member);
                }
            }
            if (functorTypes.containsKey(functor.outputSignature)) {
                if (functor.returnedFunctor == null) {
                    throw new RuntimeException("Functor " + functor.name +
                            " must declare return_functor to satisfy " + functor.outputSignature);
                }
                if (!functor.methods.isEmpty() || !functor.typeAliases.isEmpty() ||
                    !functor.typeSharing.isEmpty()) {
                    throw new RuntimeException("Functor " + functor.name +
                            " returning a functor cannot also export module methods or types");
                }
                Ast.FunctorTypeDecl required = functorTypes.get(functor.outputSignature);
                if (functor.returnedFunctor.equals(functor.argument)) {
                    Ast.FunctorTypeDecl supplied = functorTypes.get(functor.inputSignature);
                    if (supplied == null ||
                        !matches(signatures.get(required.inputSignature), signatures.get(supplied.inputSignature)) ||
                        !matches(signatures.get(supplied.outputSignature), signatures.get(required.outputSignature))) {
                        throw new RuntimeException("Functor " + functor.name +
                                " returns an incompatible functor argument");
                    }
                } else {
                    Ast.FunctorDecl supplied = allFunctors.get(functor.returnedFunctor);
                    if (!matchesFunctor(supplied, required, signatures)) {
                        throw new RuntimeException("Functor " + functor.name +
                                " returns incompatible functor " + functor.returnedFunctor);
                    }
                }
            } else {
                if (functor.returnedFunctor != null) {
                    throw new RuntimeException("Functor " + functor.name +
                            " cannot return_functor for module signature " + functor.outputSignature);
                }
                validateMethods("functor " + functor.name, functor.outputSignature,
                        functor.methods, functor.typeAliases, signatures);
            }
        }
        verifyApplications(program, signatures);
    }

    private static void validateDependentPath(Ast.FunctorDecl functor,
                                              Ast.ModuleTypeDecl input, String path) {
        String[] parts = path.split("\\.", -1);
        if (parts.length != 2 || !parts[0].equals(functor.argument) ||
            input == null || !input.abstractTypes.contains(parts[1])) {
            throw new RuntimeException("Functor " + functor.name +
                    " has invalid dependent type path " + path);
        }
    }

    /**
     * Lightweight static elaboration of module expressions.
     *
     * Known module paths, functor applications, and public API calls are
     * checked before executing main. Dynamically computed expressions still
     * receive runtime checks in Interpreter.
     */
    private static void verifyApplications(Ast.Program program,
                                           Map<String, Ast.ModuleTypeDecl> signatures) {
        Map<String, String> known = new HashMap<>();
        Map<String, Ast.FunctorDecl> functors = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules) known.put(module.name, module.signature);
        for (Ast.FunctorDecl functor : program.functors) functors.put(functor.name, functor);
        for (Ast.ModuleDecl module : program.modules) {
            for (Ast.MethodDecl method : module.methods) {
                validateStmts(method.body, new HashMap<>(known), signatures, functors);
            }
        }
        for (Ast.FunctorDecl functor : program.functors) {
            Map<String, String> locals = new HashMap<>(known);
            if (signatures.containsKey(functor.inputSignature))
                locals.put(functor.argument, functor.inputSignature);
            for (Ast.MethodDecl method : functor.methods) {
                validateStmts(method.body, new HashMap<>(locals), signatures, functors);
            }
        }
        if (program.mainBody != null) {
            validateStmts(program.mainBody, known, signatures, functors);
        }
    }

    private static void validateStmts(List<Ast.Stmt> statements,
                                      Map<String, String> known,
                                      Map<String, Ast.ModuleTypeDecl> signatures,
                                      Map<String, Ast.FunctorDecl> functors) {
        if (statements == null) return;
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.SetStmt set) {
                String inferred = inferModule(set.value, known, signatures, functors);
                if (set.typeName != null && signatures.containsKey(set.typeName) && inferred != null &&
                        !matches(signatures.get(inferred), signatures.get(set.typeName))) {
                    throw new RuntimeException("Module binding " + set.name +
                            " expects " + set.typeName + ", got " + inferred);
                }
                known.put(set.name, inferred);
            } else if (stmt instanceof Ast.PrintStmt print) {
                inferModule(print.value, known, signatures, functors);
            } else if (stmt instanceof Ast.CallStmt call) {
                inferModule(call.call, known, signatures, functors);
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                inferModule(ret.value, known, signatures, functors);
            } else if (stmt instanceof Ast.IfStmt iff) {
                inferModule(iff.condition, known, signatures, functors);
                validateStmts(iff.thenBody, new HashMap<>(known), signatures, functors);
                validateStmts(iff.elseBody, new HashMap<>(known), signatures, functors);
            } else if (stmt instanceof Ast.WhileStmt loop) {
                inferModule(loop.condition, known, signatures, functors);
                validateStmts(loop.body, new HashMap<>(known), signatures, functors);
            } else if (stmt instanceof Ast.TryStmt tr) {
                validateStmts(tr.tryBody, new HashMap<>(known), signatures, functors);
                validateStmts(tr.catchBody, new HashMap<>(known), signatures, functors);
            } else if (stmt instanceof Ast.SwitchStmt sw) {
                inferModule(sw.subject, known, signatures, functors);
                for (Ast.CaseClause clause : sw.cases) {
                    for (Ast.Expr expr : clause.values) inferModule(expr, known, signatures, functors);
                    validateStmts(clause.body, new HashMap<>(known), signatures, functors);
                }
                validateStmts(sw.defaultBody, new HashMap<>(known), signatures, functors);
            } else if (stmt instanceof Ast.SetIndexStmt index) {
                inferModule(index.index, known, signatures, functors);
                inferModule(index.value, known, signatures, functors);
            } else if (stmt instanceof Ast.SetFieldStmt field) {
                inferModule(field.target, known, signatures, functors);
                inferModule(field.value, known, signatures, functors);
            } else if (stmt instanceof Ast.SaveStmt save) {
                inferModule(save.record, known, signatures, functors);
            } else if (stmt instanceof Ast.DeleteStmt del) {
                inferModule(del.where, known, signatures, functors);
            } else if (stmt instanceof Ast.RouteStmt route) {
                validateStmts(route.body, new HashMap<>(known), signatures, functors);
            } else if (stmt instanceof Ast.RespondStmt response) {
                inferModule(response.value, known, signatures, functors);
            } else if (stmt instanceof Ast.ServeStmt serve) {
                inferModule(serve.port, known, signatures, functors);
            }
        }
    }

    /** Return a statically known module signature or null for dynamic values. */
    private static String inferModule(Ast.Expr expr, Map<String, String> known,
                                      Map<String, Ast.ModuleTypeDecl> signatures,
                                      Map<String, Ast.FunctorDecl> functors) {
        if (expr == null) return null;
        if (expr instanceof Ast.Var variable) return known.get(variable.name);
        if (expr instanceof Ast.CallExpr call) {
            List<String> inputs = new ArrayList<>();
            for (Ast.Expr arg : call.args) {
                inputs.add(inferModule(arg, known, signatures, functors));
            }
            Ast.FunctorDecl functor = functors.get(call.name);
            if (functor == null) return null;
            if (inputs.size() != 1) {
                throw moduleError(call.line, "Functor " + call.name +
                        " requires exactly one module argument");
            }
            if (inputs.get(0) != null &&
                    !matches(signatures.get(inputs.get(0)), signatures.get(functor.inputSignature))) {
                String expects = signatures.containsKey(functor.inputSignature)
                        ? functor.inputSignature : "functor " + functor.inputSignature;
                throw moduleError(call.line, "Functor " + call.name + " requires " +
                        expects + ", got " + inputs.get(0));
            }
            return functor.outputSignature;
        }
        if (expr instanceof Ast.MethodCall call) {
            String targetSignature = inferModule(call.target, known, signatures, functors);
            for (Ast.Expr arg : call.args) inferModule(arg, known, signatures, functors);
            if (targetSignature != null) {
                Ast.ModuleTypeDecl signature = signatures.get(targetSignature);
                if (signature == null) return null;
                Ast.MethodSig method = null;
                for (Ast.MethodSig m : signature.methods) {
                    if (m.name.equals(call.method)) { method = m; break; }
                }
                if (method == null) {
                    throw moduleError(call.line, "Module signature " + targetSignature +
                            " does not export " + call.method);
                }
                if (method.params.size() != call.args.size()) {
                    throw moduleError(call.line, targetSignature + "." + call.method +
                            " expects " + method.params.size() + " args, got " + call.args.size());
                }
            }
            return null;
        }
        if (expr instanceof Ast.BinOp op) {
            inferModule(op.left, known, signatures, functors);
            inferModule(op.right, known, signatures, functors);
        } else if (expr instanceof Ast.Compare op) {
            inferModule(op.left, known, signatures, functors);
            inferModule(op.right, known, signatures, functors);
        } else if (expr instanceof Ast.ArrayLit array) {
            for (Ast.Expr item : array.elements) inferModule(item, known, signatures, functors);
        } else if (expr instanceof Ast.MapLit map) {
            for (Ast.Expr item : map.values) inferModule(item, known, signatures, functors);
        } else if (expr instanceof Ast.IndexExpr idx) {
            inferModule(idx.target, known, signatures, functors);
            inferModule(idx.index, known, signatures, functors);
        } else if (expr instanceof Ast.FieldAccess field) {
            inferModule(field.target, known, signatures, functors);
        } else if (expr instanceof Ast.NewExpr call) {
            for (Ast.Expr arg : call.args) inferModule(arg, known, signatures, functors);
        } else if (expr instanceof Ast.FindExpr find) {
            inferModule(find.where, known, signatures, functors);
        }
        return null;
    }

    private static RuntimeException moduleError(int line, String message) {
        return new RuntimeException("Module type error" +
                (line > 0 ? " at line " + line : "") + ": " + message);
    }

    private static void validateMethods(String owner, String signatureName,
                                        List<Ast.MethodDecl> methods,
                                        Map<String, String> typeAliases,
                                        Map<String, Ast.ModuleTypeDecl> signatures) {
        Ast.ModuleTypeDecl signature = signatures.get(signatureName);
        if (signature == null) {
            throw new RuntimeException(owner + " references unknown module_type " + signatureName);
        }
        for (String member : signature.abstractTypes) {
            if (!typeAliases.containsKey(member)) {
                throw new RuntimeException(owner + " must define abstract type " +
                        signatureName + "." + member + " using 'type " + member + ": concrete_type'");
            }
            String manifest = signature.manifestTypes.get(member);
            if (manifest != null && !manifest.equals(typeAliases.get(member))) {
                throw new RuntimeException(owner + " must share manifest type " +
                        signatureName + "." + member + " = " + manifest);
            }
        }
        Map<String, Ast.MethodDecl> supplied = new HashMap<>();
        for (Ast.MethodDecl method : methods) {
            if (supplied.putIfAbsent(method.name, method) != null) {
                throw new RuntimeException(owner + " defines duplicate method " + method.name);
            }
        }
        for (Ast.MethodSig expected : signature.methods) {
            Ast.MethodDecl actual = supplied.get(expected.name);
            if (actual == null) {
                throw new RuntimeException(owner + " missing required export " +
                        signatureName + "." + expected.name);
            }
            if (actual.isPrivate) {
                throw new RuntimeException(owner + " export " + expected.name + " is private");
            }
            requireMethodMatch(owner + "." + expected.name, expected, actual);
        }
    }

    private static void requireMethodMatch(String path, Ast.MethodSig signature,
                                           Ast.MethodDecl actual) {
        if (signature.params.size() != actual.params.size()) {
            throw new RuntimeException(path + " arity mismatch: expected " +
                    signature.params.size() + ", got " + actual.params.size());
        }
        for (int i = 0; i < signature.params.size(); i++) {
            String expected = signature.paramTypes.get(i);
            String provided = actual.paramTypes.get(i);
            if (expected != null && !expected.equals(provided)) {
                throw new RuntimeException(path + " argument " + (i + 1) +
                        " type mismatch: expected " + expected + ", got " + provided);
            }
        }
        if (signature.returnType != null && !signature.returnType.equals(actual.returnType)) {
            throw new RuntimeException(path + " return type mismatch: expected " +
                    signature.returnType + ", got " + actual.returnType);
        }
    }

    /** Structural width matching: a module with extra public exports may be used
     * where a narrower signature is required, provided all types match.
     */
    public static boolean matches(Ast.ModuleTypeDecl actual, Ast.ModuleTypeDecl required) {
        if (actual == null || required == null) return false;
        Map<String, Ast.MethodSig> exposed = new HashMap<>();
        for (Ast.MethodSig method : actual.methods) exposed.put(method.name, method);
        if (!actual.abstractTypes.containsAll(required.abstractTypes)) return false;
        for (Map.Entry<String, String> member : required.manifestTypes.entrySet()) {
            if (!member.getValue().equals(actual.manifestTypes.get(member.getKey()))) return false;
        }
        for (Ast.MethodSig expected : required.methods) {
            Ast.MethodSig member = exposed.get(expected.name);
            if (member == null || member.params.size() != expected.params.size()) return false;
            for (int i = 0; i < expected.params.size(); i++) {
                if (!Objects.equals(expected.paramTypes.get(i), member.paramTypes.get(i))) {
                    return false;
                }
            }
            if (!Objects.equals(expected.returnType, member.returnType)) return false;
        }
        return true;
    }
}
