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
            Set<String> methods = new HashSet<>();
            for (Ast.MethodSig method : sig.methods) {
                if (!methods.add(method.name)) {
                    throw new RuntimeException("Duplicate export " + sig.name + "." + method.name);
                }
            }
        }
        return signatures;
    }

    public static void check(Ast.Program program) {
        Map<String, Ast.ModuleTypeDecl> signatures = signatures(program);
        Set<String> reserved = new HashSet<>();
        for (Ast.FnDecl fn : program.functions) reserved.add(fn.name);
        for (Ast.ClassDecl cls : program.classes) reserved.add(cls.name);
        for (Ast.InterfaceDecl iface : program.interfaces) reserved.add(iface.name);
        for (Ast.ModuleTypeDecl sig : program.moduleTypes) {
            if (!reserved.add(sig.name)) {
                throw new RuntimeException("Duplicate global declaration " + sig.name);
            }
        }
        for (Ast.ModuleDecl mod : program.modules) {
            if (!reserved.add(mod.name)) {
                throw new RuntimeException("Duplicate global declaration " + mod.name);
            }
            validateMethods(mod.name, mod.signature, mod.methods, signatures);
        }
        for (Ast.FunctorDecl functor : program.functors) {
            if (!reserved.add(functor.name)) {
                throw new RuntimeException("Duplicate global declaration " + functor.name);
            }
            if (!signatures.containsKey(functor.inputSignature)) {
                throw new RuntimeException("Functor " + functor.name +
                        " references unknown input signature " + functor.inputSignature);
            }
            if (functor.argument.equals(functor.name)) {
                throw new RuntimeException("Functor " + functor.name + " shadows its own name");
            }
            validateMethods("functor " + functor.name, functor.outputSignature,
                    functor.methods, signatures);
        }
    }

    private static void validateMethods(String owner, String signatureName,
                                        List<Ast.MethodDecl> methods,
                                        Map<String, Ast.ModuleTypeDecl> signatures) {
        Ast.ModuleTypeDecl signature = signatures.get(signatureName);
        if (signature == null) {
            throw new RuntimeException(owner + " references unknown module_type " + signatureName);
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
