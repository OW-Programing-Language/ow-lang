package dev.oreo;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class LlvmIrGen {

    private static class Value {
        final String ref;
        final String type;
        // True when an array expression creates its own reference. Storing such
        // a value transfers ownership; loading an array variable only borrows.
        final boolean owned;
        Value(String ref, String type) { this(ref, type, false); }
        Value(String ref, String type, boolean owned) {
            this.ref = ref; this.type = type; this.owned = owned;
        }
    }

    private final StringBuilder globals = new StringBuilder();
    private StringBuilder body;
    // All array slots are zero-initialized at function entry so any control
    // flow path can release them safely, even if a branch never executes.
    private StringBuilder entryAllocs;
    private int tempId;
    private int labelId;
    private int strCount;

    private final Set<String> functionNames = new HashSet<>();
    private final Map<String, Ast.FnDecl> functionSignatures = new LinkedHashMap<>();
    private String currentReturnType;
    private Map<String, String> locals;
    private Map<String, String> localTypes;

    public String generate(Ast.Program program, String moduleName) {
        globals.setLength(0);
        strCount = 0;
        functionNames.clear();
        functionSignatures.clear();
        for (Ast.FnDecl fn : program.functions) {
            if (fn.name.startsWith("__ow_native_array_") || fn.name.startsWith("__ow_native_string_")
                    || fn.name.startsWith("__ow_native_strarray_")
                    || fn.name.startsWith("__ow_native_intmap_")) {
                throw new RuntimeException("LLVM backend: reserved native runtime function name: " + fn.name);
            }
            if (!functionNames.add(fn.name)) {
                throw new RuntimeException("Duplicate function: " + fn.name);
            }
            functionSignatures.put(fn.name, fn);
        }

        StringBuilder fnsCode = new StringBuilder();
        for (Ast.FnDecl fn : program.functions) {
            fnsCode.append(emitFunction(fn));
        }
        fnsCode.append(emitMain(program.mainBody));

        StringBuilder out = new StringBuilder();
        out.append("; ModuleID = '").append(moduleName).append("'\n");
        out.append("declare i32 @printf(i8*, ...)\n");
        out.append("declare i32 @strcmp(i8*, i8*)\n");
        out.append("declare i64 @strlen(i8*)\n");
        out.append("declare i8* @memcpy(i8*, i8*, i64)\n");
        // A native integer array has length, shared backing storage and
        // reference-count pointer. The interpreter remains the full reference
        // semantics implementation; this ARC subset only covers i32 arrays.
        out.append("%ow.array = type { i32, i32*, i32* }\n");
        out.append("%ow.string = type { i8*, i32* }\n");
        // String arrays carry refcounted %ow.string elements and own them.
        out.append("%ow.strarray = type { i32, %ow.string*, i32* }\n");
        // First native dictionary subset: string keys, i32 values.
        out.append("%ow.intmap.entry = type { %ow.string, i32, %ow.intmap.entry* }\n");
        out.append("%ow.intmap = type { %ow.intmap.entry*, i32, i32 }\n");
        out.append("declare i8* @calloc(i64, i64)\n");
        out.append("declare void @free(i8*)\n");
        out.append("declare void @abort() noreturn\n");
        out.append("@.fmt_int = private constant [4 x i8] c\"%d\\0A\\00\"\n");
        out.append("@.fmt_str = private constant [4 x i8] c\"%s\\0A\\00\"\n");
        out.append("@.bool_true = private constant [5 x i8] c\"true\\00\"\n");
        out.append("@.bool_false = private constant [6 x i8] c\"false\\00\"\n");
        out.append(globals);
        out.append('\n');
        out.append(nativeArrayHelpers());
        out.append(nativeStringHelpers());
        out.append(nativeOwnedStringHelpers());
        out.append(nativeStringArrayHelpers());
        out.append(nativeIntegerMapHelpers());
        out.append(fnsCode);
        return out.toString();
    }

    /**
     * Native function ABI:
     * - i32 is the default for legacy untyped parameters and returns
     * - explicitly annotated `array` parameters own exactly one reference
     * - returning `array` transfers one owned reference to the caller.
     * Array-returning functions require a final explicit return to avoid a
     * silently synthesized null array on fallthrough.
     */
    private String emitFunction(Ast.FnDecl fn) {
        body = new StringBuilder();
        entryAllocs = new StringBuilder();
        tempId = 0;
        labelId = 0;
        locals = new LinkedHashMap<>();
        localTypes = new LinkedHashMap<>();
        currentReturnType = llvmType(fn.returnType);

        if (isManaged(currentReturnType) &&
            (fn.body.isEmpty() || !(fn.body.get(fn.body.size() - 1) instanceof Ast.ReturnStmt))) {
            throw new RuntimeException("LLVM backend: array/string-returning function '" + fn.name
                    + "' must end with an explicit return");
        }

        StringBuilder header = new StringBuilder();
        header.append("define ").append(currentReturnType).append(" @")
              .append(fn.name).append("(");
        for (int i = 0; i < fn.params.size(); i++) {
            if (i > 0) header.append(", ");
            header.append(llvmType(fn.paramTypes.get(i))).append(" %")
                  .append(fn.params.get(i)).append(".arg");
        }
        header.append(") {\nentry:\n");

        for (int i = 0; i < fn.params.size(); i++) {
            String pname = fn.params.get(i);
            String type = llvmType(fn.paramTypes.get(i));
            String ptr = "%" + pname;
            if (locals.putIfAbsent(pname, ptr) != null) {
                throw new RuntimeException("LLVM backend: duplicate parameter: " + pname);
            }
            localTypes.put(pname, type);
            if (isManaged(type)) {
                entryAllocs.append("  ").append(ptr).append(" = alloca ")
                        .append(type).append("\n");
            } else {
                body.append("  ").append(ptr).append(" = alloca ")
                        .append(type).append("\n");
            }
            // Caller transfers an owned reference into every array parameter.
            // Callee releases the parameter on any return, including the
            // implicit return for integer/void-style functions.
            body.append("  store ").append(type).append(" %").append(pname)
                    .append(".arg, ").append(type).append("* ").append(ptr).append("\n");
        }

        for (Ast.Stmt st : fn.body) genStmt(st);
        releaseAllManagedLocals();
        if (currentReturnType.equals("%ow.array") || currentReturnType.equals("%ow.strarray")) {
            // The explicit source return has already emitted the real result.
            body.append("  ret ").append(currentReturnType).append(" zeroinitializer\n");
        } else if (currentReturnType.equals("%ow.string")) {
            body.append("  ret %ow.string zeroinitializer\n");
        } else if (currentReturnType.equals("%ow.intmap*")) {
            body.append("  ret %ow.intmap* null\n");
        } else {
            body.append("  ret i32 0\n");
        }
        body.append("}\n\n");
        return header.toString() + entryAllocs + body;
    }

    private String emitMain(List<Ast.Stmt> mainBody) {
        body = new StringBuilder();
        entryAllocs = new StringBuilder();
        currentReturnType = "i32";
        tempId = 0;
        labelId = 0;
        locals = new HashMap<>();
        localTypes = new HashMap<>();

        StringBuilder header = new StringBuilder();
        header.append("define i32 @main() {\nentry:\n");

        for (Ast.Stmt s : mainBody) genStmt(s);
        releaseAllManagedLocals();
        body.append("  ret i32 0\n");
        body.append("}\n");
        return header.toString() + entryAllocs + body;
    }

    private void genStmt(Ast.Stmt st) {
        if (st instanceof Ast.SetStmt s) {
            Value v = genExpr(s.value);
            String existing = locals.get(s.name);
            if (existing == null) {
                String ptr = "%" + s.name;
                locals.put(s.name, ptr);
                localTypes.put(s.name, v.type);
                if (isManaged(v.type)) {
                    // Release works regardless of which conditional assigned
                    // this local: all managed slots start as null references.
                    entryAllocs.append("  ").append(ptr).append(" = alloca ")
                            .append(v.type).append("\n");
                    entryAllocs.append("  store ").append(v.type)
                            .append(" zeroinitializer, ").append(v.type)
                            .append("* ").append(ptr).append("\n");
                } else {
                    body.append("  ").append(ptr).append(" = alloca ")
                            .append(v.type).append("\n");
                }
            } else {
                String knownType = localTypes.get(s.name);
                if (!knownType.equals(v.type)) {
                    throw new RuntimeException("Variable '" + s.name + "' was " + knownType
                            + " but assigned " + v.type);
                }
            }
            if (isManaged(v.type)) {
                // Retain BEFORE release: self-assignment and shared aliases
                // must never observe a prematurely freed reference.
                if (!v.owned) retainManaged(v);
                releaseManagedFromSlot(locals.get(s.name), v.type);
            }
            String ptr = locals.get(s.name);
            body.append("  store ").append(v.type).append(" ").append(v.ref)
                    .append(", ").append(v.type).append("* ").append(ptr).append("\n");
            return;
        }

        if (st instanceof Ast.SetIndexStmt set) {
            // OW evaluates the container and index before the assigned value.
            Value array = genExpr(new Ast.Var(set.name));
            Value index = genExpr(set.index);
            if (array.type.equals("%ow.intmap*")) {
                requireIntegerMapKey(index);
                Value value = genExpr(set.value);
                if (!value.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: native dictionaries currently require integer values");
                }
                // map_set takes ownership of exactly one key reference.
                if (!index.owned) retainManaged(index);
                body.append("  call void @__ow_native_intmap_set(%ow.intmap* ")
                        .append(array.ref).append(", %ow.string ")
                        .append(index.ref).append(", i32 ").append(value.ref).append(")\n");
                return;
            }
            String elementPtr = checkedArrayElement(array, index);
            Value value = genExpr(set.value);
            if (array.type.equals("%ow.strarray")) {
                if (!value.type.equals("%ow.string")) {
                    throw new RuntimeException("LLVM backend: string arrays require string elements");
                }
                // Retain before releasing the previous element. This remains
                // safe for set items[0]: items[0] and shared string aliases.
                if (!value.owned) retainManaged(value);
                String old = newTemp();
                body.append("  ").append(old)
                        .append(" = load %ow.string, %ow.string* ").append(elementPtr).append("\n");
                releaseManaged(new Value(old, "%ow.string"));
                body.append("  store %ow.string ").append(value.ref)
                        .append(", %ow.string* ").append(elementPtr).append("\n");
            } else {
                if (!value.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: integer arrays require integer elements");
                }
                body.append("  store i32 ").append(value.ref).append(", i32* ")
                        .append(elementPtr).append("\n");
            }
            return;
        }

        if (st instanceof Ast.PrintStmt p) {
            Value v = genExpr(p.value);
            String fmtPtr = newTemp();
            if (v.type.equals("i1")) {
                String truePtr = newTemp();
                body.append("  ").append(truePtr)
                        .append(" = getelementptr [5 x i8], [5 x i8]* @.bool_true, i32 0, i32 0\n");
                String falsePtr = newTemp();
                body.append("  ").append(falsePtr)
                        .append(" = getelementptr [6 x i8], [6 x i8]* @.bool_false, i32 0, i32 0\n");
                String boolText = newTemp();
                body.append("  ").append(boolText).append(" = select i1 ")
                        .append(v.ref).append(", i8* ").append(truePtr)
                        .append(", i8* ").append(falsePtr).append("\n");
                body.append("  ").append(fmtPtr)
                        .append(" = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0\n");
                body.append("  call i32 (i8*, ...) @printf(i8* ").append(fmtPtr)
                        .append(", i8* ").append(boolText).append(")\n");
            } else if (v.type.equals("i32")) {
                body.append("  ").append(fmtPtr)
                        .append(" = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0\n");
                body.append("  call i32 (i8*, ...) @printf(i8* ").append(fmtPtr)
                        .append(", i32 ").append(v.ref).append(")\n");
            } else if (v.type.equals("%ow.string")) {
                String data = stringData(v);
                body.append("  ").append(fmtPtr)
                        .append(" = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0\n");
                body.append("  call i32 (i8*, ...) @printf(i8* ").append(fmtPtr)
                        .append(", i8* ").append(data).append(")\n");
                if (v.owned) releaseManaged(v);
            } else {
                throw new RuntimeException("Cannot print value of type " + v.type);
            }
            return;
        }

        if (st instanceof Ast.IfStmt iff) {
            int id = labelId++;
            String thenL = "if.then." + id;
            String elseL = "if.else." + id;
            String endL  = "if.end." + id;
            Value cond = genCond(iff.condition);
            String falseTarget = (iff.elseBody != null) ? elseL : endL;
            body.append("  br i1 ").append(cond.ref)
                    .append(", label %").append(thenL)
                    .append(", label %").append(falseTarget).append("\n");
            body.append(thenL).append(":\n");
            for (Ast.Stmt s : iff.thenBody) genStmt(s);
            body.append("  br label %").append(endL).append("\n");
            if (iff.elseBody != null) {
                body.append(elseL).append(":\n");
                for (Ast.Stmt s : iff.elseBody) genStmt(s);
                body.append("  br label %").append(endL).append("\n");
            }
            body.append(endL).append(":\n");
            return;
        }

        if (st instanceof Ast.WhileStmt w) {
            int id = labelId++;
            String condL = "while.cond." + id;
            String bodyL = "while.body." + id;
            String endL  = "while.end." + id;
            body.append("  br label %").append(condL).append("\n");
            body.append(condL).append(":\n");
            Value cond = genCond(w.condition);
            body.append("  br i1 ").append(cond.ref)
                    .append(", label %").append(bodyL)
                    .append(", label %").append(endL).append("\n");
            body.append(bodyL).append(":\n");
            for (Ast.Stmt s : w.body) genStmt(s);
            body.append("  br label %").append(condL).append("\n");
            body.append(endL).append(":\n");
            return;
        }

        if (st instanceof Ast.ReturnStmt r) {
            Value v = genExpr(r.value);
            if (!v.type.equals(currentReturnType)) {
                throw new RuntimeException("LLVM backend: expected return type "
                        + currentReturnType + " but got " + v.type);
            }
            if (isManaged(v.type) && !v.owned) {
                // Borrowed managed values are retained before local cleanup.
                retainManaged(v);
            }
            releaseAllManagedLocals();
            body.append("  ret ").append(v.type).append(" ")
                    .append(v.ref).append("\n");
            String dead = "after.return." + (labelId++);
            body.append(dead).append(":\n");
            return;
        }

        if (st instanceof Ast.CallStmt c) {
            Value result = genExpr(c.call);
            // Discarded dynamically allocated results are reclaimed here.
            if (result.owned) releaseManaged(result);
            return;
        }

        throw new RuntimeException("Unknown stmt: " + st.getClass());
    }

    private Value genCond(Ast.Expr e) {
        Value v = genExpr(e);
        if (v.type.equals("i1")) return v;
        if (v.type.equals("i32")) {
            String t = newTemp();
            body.append("  ").append(t).append(" = icmp ne i32 ").append(v.ref).append(", 0\n");
            return new Value(t, "i1");
        }
        throw new RuntimeException("Condition must be a number or comparison, got " + v.type);
    }

    private Value genExpr(Ast.Expr e) {
        if (e instanceof Ast.IntLit i) {
            return new Value(Integer.toString(i.value), "i32");
        }
        if (e instanceof Ast.StringLit str) {
            // String literals are static immortal UTF-8 bytes (null refs).
            // Dynamic strings from '+' share the same value ABI, but have
            // an allocated reference counter.
            String data = emitStringLiteral(str.value);
            String first = newTemp();
            body.append("  ").append(first).append(" = insertvalue %ow.string undef, i8* ")
                    .append(data).append(", 0\n");
            String second = newTemp();
            body.append("  ").append(second)
                    .append(" = insertvalue %ow.string ").append(first)
                    .append(", i32* null, 1\n");
            return new Value(second, "%ow.string");
        }
        if (e instanceof Ast.BoolLit b) {
            return new Value(b.value ? "1" : "0", "i32");
        }
        if (e instanceof Ast.ArrayLit a) {
            // [] keeps its existing integer-array type. Nonempty literals
            // select integer vs string elements from the first expression.
            int count = a.elements.size();
            // Evaluate the first element only once, then determine its type.
            // This supports variables, string-producing calls and expressions
            // without guessing from AST syntax or duplicating side effects.
            Value first = count > 0 ? genExpr(a.elements.get(0)) : null;
            if (first != null && first.type.equals("%ow.string")) {
                Value array = allocateStringArray(Integer.toString(count));
                for (int i = 0; i < count; i++) {
                    Value element = i == 0 ? first : genExpr(a.elements.get(i));
                    if (!element.type.equals("%ow.string")) {
                        throw new RuntimeException("LLVM backend: mixed integer/string array literal");
                    }
                    // The container becomes one owner of each element.
                    if (!element.owned) retainManaged(element);
                    String ptr = stringArrayElementPointer(array, Integer.toString(i));
                    body.append("  store %ow.string ").append(element.ref)
                            .append(", %ow.string* ").append(ptr).append("\n");
                }
                return array;
            }
            // Integer arrays retain the previous native ABI and behavior.
            Value storage = allocatedArrayData(count);
            for (int i = 0; i < count; i++) {
                Value element = i == 0 ? first : genExpr(a.elements.get(i));
                if (!element.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: mixed or unsupported array literal element types");
                }
                String item = newTemp();
                body.append("  ").append(item).append(" = getelementptr i32, i32* ")
                        .append(storage.ref).append(", i32 ").append(i).append("\n");
                body.append("  store i32 ").append(element.ref)
                        .append(", i32* ").append(item).append("\n");
            }
            return buildArrayValue(Integer.toString(count), storage.ref);
        }
        if (e instanceof Ast.MapLit map) {
            Value dictionary = createIntegerMap();
            for (int i = 0; i < map.keys.size(); i++) {
                Value key = genExpr(new Ast.StringLit(map.keys.get(i)));
                Value val = genExpr(map.values.get(i));
                if (!val.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: native dictionary literals currently require integer values");
                }
                if (!key.owned) retainManaged(key);
                body.append("  call void @__ow_native_intmap_set(%ow.intmap* ")
                        .append(dictionary.ref).append(", %ow.string ")
                        .append(key.ref).append(", i32 ").append(val.ref).append(")\n");
            }
            return dictionary;
        }
        if (e instanceof Ast.Var v) {
            String ptr = locals.get(v.name);
            if (ptr == null) throw new RuntimeException("Undefined variable: " + v.name);
            String type = localTypes.get(v.name);
            String t = newTemp();
            body.append("  ").append(t).append(" = load ").append(type)
                    .append(", ").append(type).append("* ").append(ptr).append("\n");
            return new Value(t, type);
        }
        if (e instanceof Ast.IndexExpr idx) {
            Value target = genExpr(idx.target);
            Value index = genExpr(idx.index);
            if (target.type.equals("%ow.intmap*")) {
                requireIntegerMapKey(index);
                String found = newTemp();
                body.append("  ").append(found)
                        .append(" = call i32 @__ow_native_intmap_get(%ow.intmap* ")
                        .append(target.ref).append(", %ow.string ").append(index.ref).append(")\n");
                if (index.owned) releaseManaged(index);
                if (target.owned) releaseManaged(target);
                return new Value(found, "i32");
            }
            String ptr = checkedArrayElement(target, index);
            String result = newTemp();
            if (target.type.equals("%ow.strarray")) {
                body.append("  ").append(result).append(" = load %ow.string, %ow.string* ")
                        .append(ptr).append("\n");
                Value item = new Value(result, "%ow.string");
                if (target.owned) {
                    // A temporary container is freed after the child gets its
                    // own reference. Return an owned string in this case.
                    retainManaged(item);
                    releaseManaged(target);
                    return new Value(result, "%ow.string", true);
                }
                return item;
            }
            body.append("  ").append(result).append(" = load i32, i32* ")
                    .append(ptr).append("\n");
            if (target.owned) releaseArray(target);
            return new Value(result, "i32");
        }
        if (e instanceof Ast.BinOp b) {
            Value l = genExpr(b.left);
            Value r = genExpr(b.right);
            if (l.type.equals("%ow.string") && r.type.equals("%ow.string")
                    && b.op.equals("+")) {
                String concatenated = newTemp();
                body.append("  ").append(concatenated)
                        .append(" = call %ow.string @__ow_native_string_concat(%ow.string ")
                        .append(l.ref).append(", %ow.string ").append(r.ref).append(")\n");
                if (l.owned) releaseManaged(l);
                if (r.owned) releaseManaged(r);
                return new Value(concatenated, "%ow.string", true);
            }
            if (!l.type.equals("i32") || !r.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: native arithmetic supports i32 or string + string only");
            }
            String op = switch (b.op) {
                case "+" -> "add";
                case "-" -> "sub";
                case "*" -> "mul";
                case "/" -> "sdiv";
                default -> throw new RuntimeException("Bad op: " + b.op);
            };
            String t = newTemp();
            body.append("  ").append(t).append(" = ").append(op)
                    .append(" i32 ").append(l.ref).append(", ").append(r.ref).append("\n");
            return new Value(t, "i32");
        }
        if (e instanceof Ast.Compare c) {
            Value l = genExpr(c.left);
            Value r = genExpr(c.right);
            if (l.type.equals("%ow.string") && r.type.equals("%ow.string")) {
                // Compare UTF-8 contents, not pointer identity. Dynamic
                // temporaries are released only after strcmp has consumed them.
                if (!c.op.equals("==") && !c.op.equals("!=")) {
                    throw new RuntimeException(
                            "LLVM backend: only == and != are supported for native strings");
                }
                String compared = newTemp();
                String leftBytes = stringData(l);
                String rightBytes = stringData(r);
                body.append("  ").append(compared)
                        .append(" = call i32 @strcmp(i8* ").append(leftBytes)
                        .append(", i8* ").append(rightBytes).append(")\n");
                if (l.owned) releaseManaged(l);
                if (r.owned) releaseManaged(r);
                String result = newTemp();
                body.append("  ").append(result).append(" = icmp ")
                        .append(c.op.equals("==") ? "eq" : "ne")
                        .append(" i32 ").append(compared).append(", 0\n");
                return new Value(result, "i1");
            }
            if (!l.type.equals("i32") || !r.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: comparisons require two integers or two strings");
            }
            String pred = switch (c.op) {
                case "==" -> "eq";
                case "!=" -> "ne";
                case "<"  -> "slt";
                case ">"  -> "sgt";
                case "<=" -> "sle";
                case ">=" -> "sge";
                default -> throw new RuntimeException("Bad cmp: " + c.op);
            };
            String t = newTemp();
            body.append("  ").append(t).append(" = icmp ").append(pred)
                    .append(" i32 ").append(l.ref).append(", ").append(r.ref).append("\n");
            return new Value(t, "i1");
        }
        if (e instanceof Ast.CallExpr call) {
            if (call.name.startsWith("map_")) {
                return genIntegerMapBuiltin(call);
            }
            if (call.name.equals("len")) {
                if (call.args.size() != 1)
                    throw new RuntimeException("LLVM backend: len expects one argument");
                Value array = genExpr(call.args.get(0));
                if (array.type.equals("%ow.string")) {
                    String data = stringData(array);
                    String length = newTemp();
                    body.append("  ").append(length)
                            .append(" = call i32 @__ow_native_string_utf16len(i8* ")
                            .append(data).append(")\n");
                    if (array.owned) releaseManaged(array);
                    return new Value(length, "i32");
                }
                if (!array.type.equals("%ow.array") && !array.type.equals("%ow.strarray"))
                    throw new RuntimeException("LLVM backend: len supports integer arrays, string arrays and strings");
                String length = newTemp();
                body.append("  ").append(length).append(" = extractvalue ")
                        .append(array.type).append(" ").append(array.ref).append(", 0\n");
                if (array.owned) releaseManaged(array);
                return new Value(length, "i32");
            }
            if (call.name.equals("array")) {
                if (call.args.size() != 2)
                    throw new RuntimeException("LLVM backend: array expects size and fill");
                Value count = genExpr(call.args.get(0));
                if (!count.type.equals("i32"))
                    throw new RuntimeException("LLVM backend: array size must be an integer");
                // Interpreter rejects an invalid size before evaluating the fill.
                checkNonNegativeArraySize(count);
                Value fill = genExpr(call.args.get(1));
                if (fill.type.equals("%ow.string")) {
                    Value array = allocateStringArray(count.ref);
                    fillStringArray(array, count, fill);
                    if (fill.owned) releaseManaged(fill);
                    return array;
                }
                if (!fill.type.equals("i32"))
                    throw new RuntimeException("LLVM backend: array fill must be int or string");
                Value storage = allocatedArrayData(count);
                fillArrayData(storage, count, fill);
                return buildArrayValue(count.ref, storage.ref);
            }
            if (call.name.equals("input") || call.name.equals("int")) {
                throw new RuntimeException("LLVM backend: builtin '" + call.name + "' is not supported yet");
            }
            Ast.FnDecl signature = functionSignatures.get(call.name);
            if (signature == null) {
                throw new RuntimeException("Unknown function: " + call.name);
            }
            if (call.args.size() != signature.params.size()) {
                throw new RuntimeException("LLVM backend: function '" + call.name
                        + "' expects " + signature.params.size() + " arguments, got "
                        + call.args.size());
            }
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < call.args.size(); i++) {
                Value av = genExpr(call.args.get(i));
                String expected = llvmType(signature.paramTypes.get(i));
                if (!av.type.equals(expected)) {
                    throw new RuntimeException("LLVM backend: function '" + call.name
                            + "' expects " + expected + " for argument " + (i + 1)
                            + ", got " + av.type);
                }
                // An owned temporary is moved to the callee; borrowed
                // variables are retained before the next argument is evaluated.
                if (isManaged(av.type) && !av.owned) retainManaged(av);
                if (i > 0) args.append(", ");
                args.append(expected).append(" ").append(av.ref);
            }
            String type = llvmType(signature.returnType);
            String t = newTemp();
            body.append("  ").append(t).append(" = call ").append(type).append(" @")
                    .append(call.name).append("(").append(args).append(")\n");
            return new Value(t, type, isManaged(type));
        }
        throw new RuntimeException("Unknown expr: " + e.getClass());
    }

    /** Allocate zero-initialized storage for a native integer array.
     * calloc receives an element count and a four-byte element size. Even
     * empty arrays allocate one element to avoid implementation-defined
     * calloc(0,4) behavior. Allocation failure terminates rather than
     * performing a null dereference.
     */
    private void requireIntegerMapKey(Value key) {
        if (!key.type.equals("%ow.string")) {
            throw new RuntimeException("LLVM backend: dictionary keys must be strings");
        }
    }

    private Value createIntegerMap() {
        String result = newTemp();
        body.append("  ").append(result)
                .append(" = call %ow.intmap* @__ow_native_intmap_new()\n");
        return new Value(result, "%ow.intmap*", true);
    }

    private Value mapArgument(Ast.CallExpr call, int index) {
        Value map = genExpr(call.args.get(index));
        if (!map.type.equals("%ow.intmap*")) {
            throw new RuntimeException("LLVM backend: " + call.name
                    + " expects an integer-valued dictionary");
        }
        return map;
    }

    private Value mapKeyArgument(Ast.CallExpr call, int index) {
        Value key = genExpr(call.args.get(index));
        requireIntegerMapKey(key);
        return key;
    }

    private Value genIntegerMapBuiltin(Ast.CallExpr call) {
        String name = call.name;
        int count = call.args.size();
        int arity = switch (name) {
            case "map_size" -> 1;
            case "map_has", "map_delete" -> 2;
            case "map_get", "map_set" -> 3;
            default -> throw new RuntimeException("LLVM backend: builtin '" + name
                    + "' is not yet supported for native dictionaries");
        };
        if (count != arity) {
            throw new RuntimeException("LLVM backend: " + name + " expects "
                    + arity + " argument(s)");
        }
        Value map = mapArgument(call, 0);
        if (name.equals("map_size")) {
            String result = newTemp();
            body.append("  ").append(result)
                    .append(" = call i32 @__ow_native_intmap_size(%ow.intmap* ")
                    .append(map.ref).append(")\n");
            if (map.owned) releaseManaged(map);
            return new Value(result, "i32");
        }
        Value key = mapKeyArgument(call, 1);
        if (name.equals("map_set")) {
            Value value = genExpr(call.args.get(2));
            if (!value.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: map_set requires an integer value");
            }
            // Set consumes one key reference and returns the original map.
            if (!key.owned) retainManaged(key);
            body.append("  call void @__ow_native_intmap_set(%ow.intmap* ")
                    .append(map.ref).append(", %ow.string ").append(key.ref)
                    .append(", i32 ").append(value.ref).append(")\n");
            return map;
        }
        if (name.equals("map_delete")) {
            body.append("  call void @__ow_native_intmap_delete(%ow.intmap* ")
                    .append(map.ref).append(", %ow.string ").append(key.ref).append(")\n");
            if (key.owned) releaseManaged(key);
            return map;
        }
        if (name.equals("map_has")) {
            String result = newTemp();
            body.append("  ").append(result)
                    .append(" = call i1 @__ow_native_intmap_has(%ow.intmap* ")
                    .append(map.ref).append(", %ow.string ").append(key.ref).append(")\n");
            if (key.owned) releaseManaged(key);
            if (map.owned) releaseManaged(map);
            return new Value(result, "i1");
        }
        if (name.equals("map_get")) {
            // map_get fallback is lazy: do not evaluate a fallback expression
            // when the requested key is already in the dictionary.
            String found = newTemp();
            body.append("  ").append(found)
                    .append(" = call %ow.intmap.entry* @__ow_native_intmap_find(%ow.intmap* ")
                    .append(map.ref).append(", %ow.string ").append(key.ref).append(")\n");
            String present = newTemp();
            body.append("  ").append(present).append(" = icmp ne %ow.intmap.entry* ")
                    .append(found).append(", null\n");
            int id = labelId++;
            String exists = "intmap.get.present." + id;
            String absent = "intmap.get.absent." + id;
            String end = "intmap.get.end." + id;
            String slot = newTemp();
            entryAllocs.append("  ").append(slot).append(" = alloca i32\n");
            body.append("  br i1 ").append(present).append(", label %")
                    .append(exists).append(", label %").append(absent).append("\n");
            body.append(exists).append(":\n");
            String valuePtr = newTemp();
            body.append("  ").append(valuePtr).append(" = getelementptr %ow.intmap.entry, %ow.intmap.entry* ")
                    .append(found).append(", i32 0, i32 1\n");
            String current = newTemp();
            body.append("  ").append(current).append(" = load i32, i32* ")
                    .append(valuePtr).append("\n");
            body.append("  store i32 ").append(current).append(", i32* ")
                    .append(slot).append("\n");
            body.append("  br label %").append(end).append("\n");
            body.append(absent).append(":\n");
            Value fallback = genExpr(call.args.get(2));
            if (!fallback.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: map_get fallback must be an integer");
            }
            body.append("  store i32 ").append(fallback.ref).append(", i32* ")
                    .append(slot).append("\n");
            body.append("  br label %").append(end).append("\n");
            body.append(end).append(":\n");
            String answer = newTemp();
            body.append("  ").append(answer).append(" = load i32, i32* ")
                    .append(slot).append("\n");
            if (key.owned) releaseManaged(key);
            if (map.owned) releaseManaged(map);
            return new Value(answer, "i32");
        }
        throw new IllegalStateException("Unchecked map builtin: " + name);
    }

    private Value allocatedArrayData(int count) {
        return allocatedArrayDataRaw(Integer.toString(Math.max(1, count)));
    }

    /** Runtime-sized arrays allocate at least one element, including for len=0.
     * This is a signed i32 size checked for negativity before reaching here;
     * zero extension makes the calloc byte count well-defined on 64-bit targets.
     */
    private Value allocatedArrayData(Value count) {
        if (!count.type.equals("i32"))
            throw new RuntimeException("LLVM backend: array size must be an integer");
        String empty = newTemp();
        body.append("  ").append(empty).append(" = icmp eq i32 ")
                .append(count.ref).append(", 0\n");
        String actualCount = newTemp();
        body.append("  ").append(actualCount).append(" = select i1 ")
                .append(empty).append(", i32 1, i32 ").append(count.ref).append("\n");
        String wideCount = newTemp();
        body.append("  ").append(wideCount).append(" = zext i32 ")
                .append(actualCount).append(" to i64\n");
        return allocatedArrayDataRaw(wideCount);
    }

    private Value allocatedArrayDataRaw(String count64) {
        String raw = newTemp();
        body.append("  ").append(raw).append(" = call i8* @calloc(i64 ")
                .append(count64)
                .append(", i64 4)\n");
        String ok = newTemp();
        body.append("  ").append(ok).append(" = icmp ne i8* ").append(raw).append(", null\n");
        int id = labelId++;
        String alive = "array.alloc.ok." + id;
        String failure = "array.alloc.fail." + id;
        body.append("  br i1 ").append(ok).append(", label %")
                .append(alive).append(", label %").append(failure).append("\n");
        body.append(failure).append(":\n");
        body.append("  call void @abort()\n");
        body.append("  unreachable\n");
        body.append(alive).append(":\n");
        String data = newTemp();
        body.append("  ").append(data).append(" = bitcast i8* ")
                .append(raw).append(" to i32*\n");
        return new Value(data, "i32*");
    }

    /** Reject negative dynamic sizes before evaluating fill or allocating. */
    private void checkNonNegativeArraySize(Value count) {
        String valid = newTemp();
        body.append("  ").append(valid).append(" = icmp sge i32 ")
                .append(count.ref).append(", 0\n");
        int id = labelId++;
        String ok = "array.size.ok." + id;
        String failure = "array.size.fail." + id;
        body.append("  br i1 ").append(valid).append(", label %")
                .append(ok).append(", label %").append(failure).append("\n");
        body.append(failure).append(":\n");
        body.append("  call void @abort()\n");
        body.append("  unreachable\n");
        body.append(ok).append(":\n");
    }

    /** Initialize a runtime-sized array, evaluating the fill expression once.
     * An SSA phi counter avoids an alloca for every invocation inside loops.
     */
    private void fillArrayData(Value storage, Value count, Value fill) {
        int id = labelId++;
        String start = "array.fill.start." + id;
        String cond = "array.fill.cond." + id;
        String loop = "array.fill.body." + id;
        String end = "array.fill.end." + id;
        String counter = newTemp();
        String next = newTemp();

        body.append("  br label %").append(start).append("\n");
        body.append(start).append(":\n");
        body.append("  br label %").append(cond).append("\n");
        body.append(cond).append(":\n");
        body.append("  ").append(counter).append(" = phi i32 [ 0, %")
                .append(start).append(" ], [ ").append(next)
                .append(", %").append(loop).append(" ]\n");
        String less = newTemp();
        body.append("  ").append(less).append(" = icmp slt i32 ")
                .append(counter).append(", ").append(count.ref).append("\n");
        body.append("  br i1 ").append(less).append(", label %")
                .append(loop).append(", label %").append(end).append("\n");
        body.append(loop).append(":\n");
        String address = newTemp();
        body.append("  ").append(address).append(" = getelementptr i32, i32* ")
                .append(storage.ref).append(", i32 ").append(counter).append("\n");
        body.append("  store i32 ").append(fill.ref).append(", i32* ")
                .append(address).append("\n");
        body.append("  ").append(next).append(" = add i32 ")
                .append(counter).append(", 1\n");
        body.append("  br label %").append(cond).append("\n");
        body.append(end).append(":\n");
    }

    /** Native string arrays are separately typed to protect the integer
     * array ABI and ensure their child string references can be released.
     * The runtime allocates zero-initialized element slots and one owner.
     */
    private Value allocateStringArray(String length) {
        String result = newTemp();
        body.append("  ").append(result)
                .append(" = call %ow.strarray @__ow_native_strarray_new(i32 ")
                .append(length).append(")\n");
        return new Value(result, "%ow.strarray", true);
    }

    private String stringArrayElementPointer(Value array, String index) {
        String storage = newTemp();
        body.append("  ").append(storage).append(" = extractvalue %ow.strarray ")
                .append(array.ref).append(", 1\n");
        String ptr = newTemp();
        body.append("  ").append(ptr)
                .append(" = getelementptr %ow.string, %ow.string* ")
                .append(storage).append(", i32 ").append(index).append("\n");
        return ptr;
    }

    /** Each element retains the one fill value: filling a string array
     * must never give multiple slots the same single owned reference.
     * Finally release the fill temporary once (handled by the caller).
     */
    private void fillStringArray(Value array, Value count, Value fill) {
        int id = labelId++;
        String start = "strarray.fill.start." + id;
        String condition = "strarray.fill.cond." + id;
        String loop = "strarray.fill.body." + id;
        String end = "strarray.fill.end." + id;
        String counter = newTemp();
        String next = newTemp();
        body.append("  br label %").append(start).append("\n");
        body.append(start).append(":\n");
        body.append("  br label %").append(condition).append("\n");
        body.append(condition).append(":\n");
        body.append("  ").append(counter).append(" = phi i32 [ 0, %")
                .append(start).append(" ], [ ").append(next)
                .append(", %").append(loop).append(" ]\n");
        String isLess = newTemp();
        body.append("  ").append(isLess).append(" = icmp slt i32 ")
                .append(counter).append(", ").append(count.ref).append("\n");
        body.append("  br i1 ").append(isLess).append(", label %")
                .append(loop).append(", label %").append(end).append("\n");
        body.append(loop).append(":\n");
        retainManaged(fill);
        String ptr = stringArrayElementPointer(array, counter);
        body.append("  store %ow.string ").append(fill.ref)
                .append(", %ow.string* ").append(ptr).append("\n");
        body.append("  ").append(next).append(" = add i32 ")
                .append(counter).append(", 1\n");
        body.append("  br label %").append(condition).append("\n");
        body.append(end).append(":\n");
    }

    private Value buildArrayValue(String length, String data) {
        // A newly created native array owns exactly one reference. Each
        // independently allocated refcount is freed with its backing store.
        String refsRaw = newTemp();
        body.append("  ").append(refsRaw)
                .append(" = call i8* @calloc(i64 1, i64 4)\n");
        String valid = newTemp();
        body.append("  ").append(valid)
                .append(" = icmp ne i8* ").append(refsRaw).append(", null\n");
        int id = labelId++;
        String ok = "array.refs.ok." + id;
        String failure = "array.refs.fail." + id;
        body.append("  br i1 ").append(valid).append(", label %")
                .append(ok).append(", label %").append(failure).append("\n");
        body.append(failure).append(":\n");
        body.append("  call void @abort()\n");
        body.append("  unreachable\n");
        body.append(ok).append(":\n");
        String refs = newTemp();
        body.append("  ").append(refs).append(" = bitcast i8* ")
                .append(refsRaw).append(" to i32*\n");
        body.append("  store i32 1, i32* ").append(refs).append("\n");
        String header = newTemp();
        body.append("  ").append(header)
                .append(" = insertvalue %ow.array undef, i32 ")
                .append(length).append(", 0\n");
        String joined = newTemp();
        body.append("  ").append(joined)
                .append(" = insertvalue %ow.array ").append(header)
                .append(", i32* ").append(data).append(", 1\n");
        String withRefs = newTemp();
        body.append("  ").append(withRefs)
                .append(" = insertvalue %ow.array ").append(joined)
                .append(", i32* ").append(refs).append(", 2\n");
        return new Value(withRefs, "%ow.array", true);
    }

    private String checkedArrayElement(Value array, Value index) {
        if (!array.type.equals("%ow.array") && !array.type.equals("%ow.strarray"))
            throw new RuntimeException("LLVM backend: indexing requires an integer or string array");
        if (!index.type.equals("i32"))
            throw new RuntimeException("LLVM backend: array indices must be integers");
        String size = newTemp();
        body.append("  ").append(size).append(" = extractvalue ")
                .append(array.type).append(" ").append(array.ref).append(", 0\n");
        String nonNegative = newTemp();
        body.append("  ").append(nonNegative).append(" = icmp sge i32 ")
                .append(index.ref).append(", 0\n");
        String belowLength = newTemp();
        body.append("  ").append(belowLength).append(" = icmp slt i32 ")
                .append(index.ref).append(", ").append(size).append("\n");
        String valid = newTemp();
        body.append("  ").append(valid).append(" = and i1 ")
                .append(nonNegative).append(", ").append(belowLength).append("\n");
        int id = labelId++;
        String inside = "array.index.ok." + id;
        String failure = "array.index.fail." + id;
        body.append("  br i1 ").append(valid).append(", label %")
                .append(inside).append(", label %").append(failure).append("\n");
        body.append(failure).append(":\n");
        body.append("  call void @abort()\n");
        body.append("  unreachable\n");
        body.append(inside).append(":\n");
        if (array.type.equals("%ow.strarray")) {
            return stringArrayElementPointer(array, index.ref);
        }
        String data = newTemp();
        body.append("  ").append(data).append(" = extractvalue %ow.array ")
                .append(array.ref).append(", 1\n");
        String element = newTemp();
        body.append("  ").append(element).append(" = getelementptr i32, i32* ")
                .append(data).append(", i32 ").append(index.ref).append("\n");
        return element;
    }

    private void retainArray(Value array) {
        body.append("  call void @__ow_native_array_retain(%ow.array ")
                .append(array.ref).append(")\n");
    }

    private void releaseArray(Value array) {
        body.append("  call void @__ow_native_array_release(%ow.array ")
                .append(array.ref).append(")\n");
    }

    private void releaseArrayFromSlot(String ptr) {
        String old = newTemp();
        body.append("  ").append(old).append(" = load %ow.array, %ow.array* ")
                .append(ptr).append("\n");
        releaseArray(new Value(old, "%ow.array"));
    }

    private static boolean isManaged(String type) {
        return type.equals("%ow.array") || type.equals("%ow.string")
                || type.equals("%ow.strarray") || type.equals("%ow.intmap*");
    }

    private String stringData(Value string) {
        String data = newTemp();
        body.append("  ").append(data).append(" = extractvalue %ow.string ")
                .append(string.ref).append(", 0\n");
        return data;
    }

    private void retainManaged(Value value) {
        if (value.type.equals("%ow.array")) retainArray(value);
        else if (value.type.equals("%ow.intmap*"))
            body.append("  call void @__ow_native_intmap_retain(%ow.intmap* ")
                    .append(value.ref).append(")\n");
        else if (value.type.equals("%ow.strarray"))
            body.append("  call void @__ow_native_strarray_retain(%ow.strarray ")
                    .append(value.ref).append(")\n");
        else if (value.type.equals("%ow.string"))
            body.append("  call void @__ow_native_string_retain(%ow.string ")
                    .append(value.ref).append(")\n");
        else throw new IllegalStateException("Not managed: " + value.type);
    }

    private void releaseManaged(Value value) {
        if (value.type.equals("%ow.array")) releaseArray(value);
        else if (value.type.equals("%ow.intmap*"))
            body.append("  call void @__ow_native_intmap_release(%ow.intmap* ")
                    .append(value.ref).append(")\n");
        else if (value.type.equals("%ow.strarray"))
            body.append("  call void @__ow_native_strarray_release(%ow.strarray ")
                    .append(value.ref).append(")\n");
        else if (value.type.equals("%ow.string"))
            body.append("  call void @__ow_native_string_release(%ow.string ")
                    .append(value.ref).append(")\n");
        else throw new IllegalStateException("Not managed: " + value.type);
    }

    private void releaseManagedFromSlot(String ptr, String type) {
        String old = newTemp();
        body.append("  ").append(old).append(" = load ").append(type)
                .append(", ").append(type).append("* ").append(ptr).append("\n");
        releaseManaged(new Value(old, type));
    }

    private void releaseAllManagedLocals() {
        for (Map.Entry<String, String> entry : localTypes.entrySet()) {
            if (isManaged(entry.getValue()))
                releaseManagedFromSlot(locals.get(entry.getKey()), entry.getValue());
        }
    }

    /** Managed string-array runtime. A string_array owns its elements:
     * last owner destruction walks every child and releases the string.
     * Works with statically allocated literals and heap string concat values.
     */
    /** Native string-keyed dictionaries restricted to i32 values.
     * The singly-linked nodes live on the heap and preserve aliases.
     * Dynamic key strings are owned by the nodes, and released with them.
     */
    private static String nativeIntegerMapHelpers() {
        return """
                define %ow.intmap* @__ow_native_intmap_new() {
                entry:
                  %end = getelementptr %ow.intmap, %ow.intmap* null, i32 1
                  %bytes = ptrtoint %ow.intmap* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %created, label %oom
                oom:
                  call void @abort()
                  unreachable
                created:
                  %map = bitcast i8* %raw to %ow.intmap*
                  %refs = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 1
                  store i32 1, i32* %refs
                  ret %ow.intmap* %map
                }

                define void @__ow_native_intmap_retain(%ow.intmap* %map) {
                entry:
                  %absent = icmp eq %ow.intmap* %map, null
                  br i1 %absent, label %done, label %retain
                retain:
                  %refs = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = add i32 %before, 1
                  store i32 %after, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_intmap_release(%ow.intmap* %map) {
                entry:
                  %absent = icmp eq %ow.intmap* %map, null
                  br i1 %absent, label %done, label %decrement
                decrement:
                  %refs = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 1
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %headPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 0
                  %head = load %ow.intmap.entry*, %ow.intmap.entry** %headPtr
                  br label %loop
                loop:
                  %entry = phi %ow.intmap.entry* [ %head, %startCleanup ], [ %next, %freeNode ]
                  %empty = icmp eq %ow.intmap.entry* %entry, null
                  br i1 %empty, label %disposeMap, label %freeNode
                freeNode:
                  %keyPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %entry, i32 0, i32 0
                  %key = load %ow.string, %ow.string* %keyPtr
                  call void @__ow_native_string_release(%ow.string %key)
                  %nextPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %entry, i32 0, i32 2
                  %next = load %ow.intmap.entry*, %ow.intmap.entry** %nextPtr
                  %rawNode = bitcast %ow.intmap.entry* %entry to i8*
                  call void @free(i8* %rawNode)
                  br label %loop
                disposeMap:
                  %rawMap = bitcast %ow.intmap* %map to i8*
                  call void @free(i8* %rawMap)
                  br label %done
                done:
                  ret void
                }

                define %ow.intmap.entry* @__ow_native_intmap_find(%ow.intmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 0
                  %head = load %ow.intmap.entry*, %ow.intmap.entry** %headPtr
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %node = phi %ow.intmap.entry* [ %head, %entry ], [ %next, %advance ]
                  %empty = icmp eq %ow.intmap.entry* %node, null
                  br i1 %empty, label %missing, label %compare
                compare:
                  %storedPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %storedPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %equal = icmp eq i32 %diff, 0
                  br i1 %equal, label %found, label %advance
                advance:
                  %nextPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 2
                  %next = load %ow.intmap.entry*, %ow.intmap.entry** %nextPtr
                  br label %loop
                found:
                  ret %ow.intmap.entry* %node
                missing:
                  ret %ow.intmap.entry* null
                }

                define void @__ow_native_intmap_set(%ow.intmap* %map, %ow.string %key, i32 %value) {
                entry:
                  %old = call %ow.intmap.entry* @__ow_native_intmap_find(%ow.intmap* %map, %ow.string %key)
                  %absent = icmp eq %ow.intmap.entry* %old, null
                  br i1 %absent, label %insert, label %update
                update:
                  %valuePtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %old, i32 0, i32 1
                  store i32 %value, i32* %valuePtr
                  call void @__ow_native_string_release(%ow.string %key)
                  br label %done
                insert:
                  %end = getelementptr %ow.intmap.entry, %ow.intmap.entry* null, i32 1
                  %bytes = ptrtoint %ow.intmap.entry* %end to i64
                  %raw = call i8* @calloc(i64 1, i64 %bytes)
                  %ok = icmp ne i8* %raw, null
                  br i1 %ok, label %store, label %oom
                oom:
                  call void @__ow_native_string_release(%ow.string %key)
                  call void @abort()
                  unreachable
                store:
                  %node = bitcast i8* %raw to %ow.intmap.entry*
                  %keyPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 0
                  store %ow.string %key, %ow.string* %keyPtr
                  %slot = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 1
                  store i32 %value, i32* %slot
                  %headPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 0
                  %previous = load %ow.intmap.entry*, %ow.intmap.entry** %headPtr
                  %nextPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 2
                  store %ow.intmap.entry* %previous, %ow.intmap.entry** %nextPtr
                  store %ow.intmap.entry* %node, %ow.intmap.entry** %headPtr
                  %countPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = add i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                define i32 @__ow_native_intmap_get(%ow.intmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.intmap.entry* @__ow_native_intmap_find(%ow.intmap* %map, %ow.string %key)
                  %missing = icmp eq %ow.intmap.entry* %found, null
                  br i1 %missing, label %noKey, label %hasKey
                noKey:
                  call void @abort()
                  unreachable
                hasKey:
                  %ptr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %found, i32 0, i32 1
                  %value = load i32, i32* %ptr
                  ret i32 %value
                }

                define i1 @__ow_native_intmap_has(%ow.intmap* %map, %ow.string %key) {
                entry:
                  %found = call %ow.intmap.entry* @__ow_native_intmap_find(%ow.intmap* %map, %ow.string %key)
                  %present = icmp ne %ow.intmap.entry* %found, null
                  ret i1 %present
                }

                define i32 @__ow_native_intmap_size(%ow.intmap* %map) {
                entry:
                  %countPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  ret i32 %count
                }

                define void @__ow_native_intmap_delete(%ow.intmap* %map, %ow.string %key) {
                entry:
                  %headPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 0
                  %requested = extractvalue %ow.string %key, 0
                  br label %loop
                loop:
                  %link = phi %ow.intmap.entry** [ %headPtr, %entry ], [ %nextLink, %advance ]
                  %node = load %ow.intmap.entry*, %ow.intmap.entry** %link
                  %empty = icmp eq %ow.intmap.entry* %node, null
                  br i1 %empty, label %done, label %compare
                compare:
                  %keyPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 0
                  %stored = load %ow.string, %ow.string* %keyPtr
                  %storedBytes = extractvalue %ow.string %stored, 0
                  %diff = call i32 @strcmp(i8* %requested, i8* %storedBytes)
                  %same = icmp eq i32 %diff, 0
                  br i1 %same, label %remove, label %advance
                advance:
                  %nextLink = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 2
                  br label %loop
                remove:
                  %nextPtr = getelementptr %ow.intmap.entry, %ow.intmap.entry* %node, i32 0, i32 2
                  %next = load %ow.intmap.entry*, %ow.intmap.entry** %nextPtr
                  store %ow.intmap.entry* %next, %ow.intmap.entry** %link
                  call void @__ow_native_string_release(%ow.string %stored)
                  %rawNode = bitcast %ow.intmap.entry* %node to i8*
                  call void @free(i8* %rawNode)
                  %countPtr = getelementptr %ow.intmap, %ow.intmap* %map, i32 0, i32 2
                  %count = load i32, i32* %countPtr
                  %countNext = sub i32 %count, 1
                  store i32 %countNext, i32* %countPtr
                  br label %done
                done:
                  ret void
                }

                """;
    }

    private static String nativeStringArrayHelpers() {
        return """
                define %ow.strarray @__ow_native_strarray_new(i32 %length) {
                entry:
                  %validLength = icmp sge i32 %length, 0
                  br i1 %validLength, label %allocation, label %invalid
                invalid:
                  call void @abort()
                  unreachable
                allocation:
                  %empty = icmp eq i32 %length, 0
                  %slots = select i1 %empty, i32 1, i32 %length
                  %slots64 = zext i32 %slots to i64
                  %elementEnd = getelementptr %ow.string, %ow.string* null, i32 1
                  %elementSize = ptrtoint %ow.string* %elementEnd to i64
                  %raw = call i8* @calloc(i64 %slots64, i64 %elementSize)
                  %allocated = icmp ne i8* %raw, null
                  br i1 %allocated, label %allocateRefs, label %oom
                oom:
                  call void @abort()
                  unreachable
                allocateRefs:
                  %refsRaw = call i8* @calloc(i64 1, i64 4)
                  %refsValid = icmp ne i8* %refsRaw, null
                  br i1 %refsValid, label %ready, label %refsOom
                refsOom:
                  call void @free(i8* %raw)
                  call void @abort()
                  unreachable
                ready:
                  %data = bitcast i8* %raw to %ow.string*
                  %refs = bitcast i8* %refsRaw to i32*
                  store i32 1, i32* %refs
                  %header = insertvalue %ow.strarray undef, i32 %length, 0
                  %withData = insertvalue %ow.strarray %header, %ow.string* %data, 1
                  %result = insertvalue %ow.strarray %withData, i32* %refs, 2
                  ret %ow.strarray %result
                }

                define void @__ow_native_strarray_retain(%ow.strarray %array) {
                entry:
                  %refs = extractvalue %ow.strarray %array, 2
                  %absent = icmp eq i32* %refs, null
                  br i1 %absent, label %done, label %inc
                inc:
                  %old = load i32, i32* %refs
                  %new = add i32 %old, 1
                  store i32 %new, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_strarray_release(%ow.strarray %array) {
                entry:
                  %refs = extractvalue %ow.strarray %array, 2
                  %absent = icmp eq i32* %refs, null
                  br i1 %absent, label %done, label %dec
                dec:
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %startCleanup, label %done
                startCleanup:
                  %size = extractvalue %ow.strarray %array, 0
                  %data = extractvalue %ow.strarray %array, 1
                  br label %loop
                loop:
                  %i = phi i32 [ 0, %startCleanup ], [ %next, %releaseElement ]
                  %hasNext = icmp slt i32 %i, %size
                  br i1 %hasNext, label %releaseElement, label %dispose
                releaseElement:
                  %ptr = getelementptr %ow.string, %ow.string* %data, i32 %i
                  %string = load %ow.string, %ow.string* %ptr
                  call void @__ow_native_string_release(%ow.string %string)
                  %next = add i32 %i, 1
                  br label %loop
                dispose:
                  %rawData = bitcast %ow.string* %data to i8*
                  call void @free(i8* %rawData)
                  %rawRefs = bitcast i32* %refs to i8*
                  call void @free(i8* %rawRefs)
                  br label %done
                done:
                  ret void
                }

                """;
    }

    private static String nativeArrayHelpers() {
        return """
                define void @__ow_native_array_retain(%ow.array %array) {
                entry:
                  %refs = extractvalue %ow.array %array, 2
                  %absent = icmp eq i32* %refs, null
                  br i1 %absent, label %done, label %increment
                increment:
                  %before = load i32, i32* %refs
                  %after = add i32 %before, 1
                  store i32 %after, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_array_release(%ow.array %array) {
                entry:
                  %refs = extractvalue %ow.array %array, 2
                  %absent = icmp eq i32* %refs, null
                  br i1 %absent, label %done, label %decrement
                decrement:
                  %before = load i32, i32* %refs
                  %after = sub i32 %before, 1
                  store i32 %after, i32* %refs
                  %last = icmp eq i32 %after, 0
                  br i1 %last, label %dispose, label %done
                dispose:
                  %data = extractvalue %ow.array %array, 1
                  %rawData = bitcast i32* %data to i8*
                  call void @free(i8* %rawData)
                  %rawRefs = bitcast i32* %refs to i8*
                  call void @free(i8* %rawRefs)
                  br label %done
                done:
                  ret void
                }

                """;
    }

    /**
     * OW's interpreter uses Java String.length(), which counts UTF-16 code
     * units. Count UTF-8 leading bytes, adding 2 for a 4-byte codepoint and
     * 1 for other starts, while skipping continuation bytes. Since strings
     * here originate from Java-encoded source literals (not arbitrary input),
     * this operates on well-formed UTF-8 and preserves non-BMP length = 2.
     */
    /**
     * A managed string consists of immutable UTF-8 bytes and an optional
     * reference counter. Literal strings have null refs and are immortal.
     * Concatenations allocate two independently reclaimed buffers.
     */
    private static String nativeOwnedStringHelpers() {
        return """
                define void @__ow_native_string_retain(%ow.string %value) {
                entry:
                  %refs = extractvalue %ow.string %value, 1
                  %static = icmp eq i32* %refs, null
                  br i1 %static, label %done, label %increment
                increment:
                  %count = load i32, i32* %refs
                  %next = add i32 %count, 1
                  store i32 %next, i32* %refs
                  br label %done
                done:
                  ret void
                }

                define void @__ow_native_string_release(%ow.string %value) {
                entry:
                  %refs = extractvalue %ow.string %value, 1
                  %static = icmp eq i32* %refs, null
                  br i1 %static, label %done, label %decrement
                decrement:
                  %count = load i32, i32* %refs
                  %next = sub i32 %count, 1
                  store i32 %next, i32* %refs
                  %last = icmp eq i32 %next, 0
                  br i1 %last, label %dispose, label %done
                dispose:
                  %data = extractvalue %ow.string %value, 0
                  call void @free(i8* %data)
                  %refsAsBytes = bitcast i32* %refs to i8*
                  call void @free(i8* %refsAsBytes)
                  br label %done
                done:
                  ret void
                }

                define %ow.string @__ow_native_string_concat(%ow.string %left, %ow.string %right) {
                entry:
                  %ltext = extractvalue %ow.string %left, 0
                  %rtext = extractvalue %ow.string %right, 0
                  %llen = call i64 @strlen(i8* %ltext)
                  %rlen = call i64 @strlen(i8* %rtext)
                  %sum = add i64 %llen, %rlen
                  %lenOk = icmp uge i64 %sum, %llen
                  %bytes = add i64 %sum, 1
                  %sizeOk = icmp ne i64 %bytes, 0
                  %valid = and i1 %lenOk, %sizeOk
                  br i1 %valid, label %allocate, label %invalid
                invalid:
                  call void @abort()
                  unreachable
                allocate:
                  %data = call i8* @calloc(i64 %bytes, i64 1)
                  %dataValid = icmp ne i8* %data, null
                  br i1 %dataValid, label %copy, label %allocFailure
                allocFailure:
                  call void @abort()
                  unreachable
                copy:
                  %first = call i8* @memcpy(i8* %data, i8* %ltext, i64 %llen)
                  %tail = getelementptr i8, i8* %data, i64 %llen
                  %rightWithTerminator = add i64 %rlen, 1
                  %second = call i8* @memcpy(i8* %tail, i8* %rtext, i64 %rightWithTerminator)
                  %refsRaw = call i8* @calloc(i64 1, i64 4)
                  %refsValid = icmp ne i8* %refsRaw, null
                  br i1 %refsValid, label %done, label %refsFailure
                refsFailure:
                  call void @free(i8* %data)
                  call void @abort()
                  unreachable
                done:
                  %refs = bitcast i8* %refsRaw to i32*
                  store i32 1, i32* %refs
                  %header = insertvalue %ow.string undef, i8* %data, 0
                  %result = insertvalue %ow.string %header, i32* %refs, 1
                  ret %ow.string %result
                }

                """;
    }

    private static String nativeStringHelpers() {
        return """
                define i32 @__ow_native_string_utf16len(i8* %text) {
                entry:
                  br label %loop
                loop:
                  %cursor = phi i8* [ %text, %entry ], [ %next, %body ]
                  %length = phi i32 [ 0, %entry ], [ %advanced, %body ]
                  %byte = load i8, i8* %cursor
                  %finished = icmp eq i8 %byte, 0
                  br i1 %finished, label %exit, label %body
                body:
                  %unsigned = zext i8 %byte to i32
                  %maskedContinuation = and i32 %unsigned, 192
                  %continuation = icmp eq i32 %maskedContinuation, 128
                  %maskedFourByte = and i32 %unsigned, 248
                  %fourByte = icmp eq i32 %maskedFourByte, 240
                  %unitsForStart = select i1 %fourByte, i32 2, i32 1
                  %units = select i1 %continuation, i32 0, i32 %unitsForStart
                  %advanced = add i32 %length, %units
                  %next = getelementptr i8, i8* %cursor, i64 1
                  br label %loop
                exit:
                  ret i32 %length
                }

                """;
    }

    /** Native subset supports int/bool scalars and explicitly annotated
     * integer arrays; other source types must not silently lower to i32.
     */
    private static String llvmType(String declaredType) {
        if (declaredType == null || declaredType.equals("int") || declaredType.equals("bool")) {
            return "i32";
        }
        if (declaredType.equals("array") || declaredType.equals("list")) {
            return "%ow.array";
        }
        if (declaredType.equals("string_array")) {
            return "%ow.strarray";
        }
        if (declaredType.equals("int_map")) {
            return "%ow.intmap*";
        }
        if (declaredType.equals("string")) {
            return "%ow.string";
        }
        throw new RuntimeException("LLVM backend: unsupported native function type '"
                + declaredType + "' (supported: int, bool, array, string)");
    }

    private String emitStringLiteral(String s) {
        // C-string representation is intentionally narrow until OW introduces
        // a length-aware owned string runtime. NUL would truncate strcmp/len.
        if (s.indexOf('\u0000') >= 0) {
            throw new RuntimeException("LLVM backend: embedded NUL in native strings is not supported yet");
        }
        int id = strCount++;
        String name = "@.str." + id;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder enc = new StringBuilder();
        for (byte b : bytes) {
            int x = b & 0xFF;
            if (x >= 32 && x < 127 && x != '"' && x != '\\') {
                enc.append((char) x);
            } else {
                enc.append(String.format("\\%02X", x));
            }
        }
        enc.append("\\00");
        int totalLen = bytes.length + 1;
        globals.append(name).append(" = private constant [").append(totalLen)
                .append(" x i8] c\"").append(enc).append("\"\n");
        String t = newTemp();
        body.append("  ").append(t).append(" = getelementptr [").append(totalLen)
                .append(" x i8], [").append(totalLen).append(" x i8]* ").append(name)
                .append(", i32 0, i32 0\n");
        return t;
    }

    private String newTemp() { return "%t" + (tempId++); }
}
