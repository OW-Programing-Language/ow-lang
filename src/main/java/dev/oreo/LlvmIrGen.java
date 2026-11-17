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
            if (fn.name.startsWith("__ow_native_array_")) {
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
        // A native integer array has length, shared backing storage and
        // reference-count pointer. The interpreter remains the full reference
        // semantics implementation; this ARC subset only covers i32 arrays.
        out.append("%ow.array = type { i32, i32*, i32* }\n");
        out.append("declare i8* @calloc(i64, i64)\n");
        out.append("declare void @free(i8*)\n");
        out.append("declare void @abort() noreturn\n");
        out.append("@.fmt_int = private constant [4 x i8] c\"%d\\0A\\00\"\n");
        out.append("@.fmt_str = private constant [4 x i8] c\"%s\\0A\\00\"\n");
        out.append(globals);
        out.append('\n');
        out.append(nativeArrayHelpers());
        out.append(nativeStringHelpers());
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

        if ((currentReturnType.equals("%ow.array") || currentReturnType.equals("i8*")) &&
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
            if (type.equals("%ow.array")) {
                entryAllocs.append("  ").append(ptr).append(" = alloca %ow.array\n");
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
        releaseAllArrayLocals();
        if (currentReturnType.equals("%ow.array")) {
            // This return is in the unreachable after.return block when the
            // final source statement is an explicit return.
            body.append("  ret %ow.array zeroinitializer\n");
        } else if (currentReturnType.equals("i8*")) {
            body.append("  ret i8* null\n");
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
        releaseAllArrayLocals();
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
                if (v.type.equals("%ow.array")) {
                    // A variable first seen in a conditional or loop must
                    // have an initialized slot on every control-flow path.
                    entryAllocs.append("  ").append(ptr)
                            .append(" = alloca %ow.array\n");
                    entryAllocs.append("  store %ow.array zeroinitializer, %ow.array* ")
                            .append(ptr).append("\n");
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
            if (v.type.equals("%ow.array")) {
                // Retain BEFORE releasing the former owner so "set a: a"
                // and aliases sharing the same allocation stay valid.
                if (!v.owned) retainArray(v);
                releaseArrayFromSlot(locals.get(s.name));
            }
            String ptr = locals.get(s.name);
            body.append("  store ").append(v.type).append(" ").append(v.ref)
                    .append(", ").append(v.type).append("* ").append(ptr).append("\n");
            return;
        }

        if (st instanceof Ast.SetIndexStmt set) {
            // OW evaluates the array and index before the assigned value.
            Value array = genExpr(new Ast.Var(set.name));
            Value index = genExpr(set.index);
            String elementPtr = checkedArrayElement(array, index);
            Value value = genExpr(set.value);
            if (!value.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: native arrays currently store only integers");
            }
            body.append("  store i32 ").append(value.ref).append(", i32* ")
                    .append(elementPtr).append("\n");
            return;
        }

        if (st instanceof Ast.PrintStmt p) {
            Value v = genExpr(p.value);
            String fmtPtr = newTemp();
            if (v.type.equals("i32")) {
                body.append("  ").append(fmtPtr)
                        .append(" = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0\n");
                body.append("  call i32 (i8*, ...) @printf(i8* ").append(fmtPtr)
                        .append(", i32 ").append(v.ref).append(")\n");
            } else if (v.type.equals("i8*")) {
                body.append("  ").append(fmtPtr)
                        .append(" = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0\n");
                body.append("  call i32 (i8*, ...) @printf(i8* ").append(fmtPtr)
                        .append(", i8* ").append(v.ref).append(")\n");
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
            if (v.type.equals("%ow.array") && !v.owned) {
                // Borrowed array returned from a local/parameter must survive
                // releasing that local's reference on function exit.
                retainArray(v);
            }
            releaseAllArrayLocals();
            body.append("  ret ").append(v.type).append(" ")
                    .append(v.ref).append("\n");
            String dead = "after.return." + (labelId++);
            body.append(dead).append(":\n");
            return;
        }

        if (st instanceof Ast.CallStmt c) {
            Value result = genExpr(c.call);
            // An unassigned array(...) expression owns a temporary reference.
            if (result.owned) releaseArray(result);
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
        if (e instanceof Ast.StringLit s) {
            return new Value(emitStringLiteral(s.value), "i8*");
        }
        if (e instanceof Ast.BoolLit b) {
            return new Value(b.value ? "1" : "0", "i32");
        }
        if (e instanceof Ast.ArrayLit a) {
            // Array contents are heap-backed. Aliases share mutations, while
            // retaining/releasing references prevents premature frees.
            int count = a.elements.size();
            Value storage = allocatedArrayData(count);
            for (int i = 0; i < count; i++) {
                Value element = genExpr(a.elements.get(i));
                if (!element.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: native arrays currently store only integers");
                }
                String item = newTemp();
                body.append("  ").append(item).append(" = getelementptr i32, i32* ")
                        .append(storage.ref).append(", i32 ").append(i).append("\n");
                body.append("  store i32 ").append(element.ref)
                        .append(", i32* ").append(item).append("\n");
            }
            return buildArrayValue(Integer.toString(count), storage.ref);
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
            String ptr = checkedArrayElement(target, index);
            String result = newTemp();
            body.append("  ").append(result).append(" = load i32, i32* ")
                    .append(ptr).append("\n");
            // Literal/dynamically created temporary arrays must not leak.
            if (target.owned) releaseArray(target);
            return new Value(result, "i32");
        }
        if (e instanceof Ast.BinOp b) {
            Value l = genExpr(b.left);
            Value r = genExpr(b.right);
            if (!l.type.equals("i32") || !r.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: arithmetic requires integers");
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
            if (l.type.equals("i8*") && r.type.equals("i8*")) {
                // Compare contents, not pointer identity. Immutable native
                // strings are UTF-8; byte equality matches Java String.equals
                // for valid UTF-8 (embedded NUL is explicitly rejected).
                if (!c.op.equals("==") && !c.op.equals("!=")) {
                    throw new RuntimeException(
                            "LLVM backend: only == and != are supported for native strings");
                }
                String compared = newTemp();
                body.append("  ").append(compared)
                        .append(" = call i32 @strcmp(i8* ").append(l.ref)
                        .append(", i8* ").append(r.ref).append(")\n");
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
            if (call.name.equals("len")) {
                if (call.args.size() != 1)
                    throw new RuntimeException("LLVM backend: len expects one argument");
                Value array = genExpr(call.args.get(0));
                if (array.type.equals("i8*")) {
                    String length = newTemp();
                    body.append("  ").append(length)
                            .append(" = call i32 @__ow_native_string_utf16len(i8* ")
                            .append(array.ref).append(")\n");
                    return new Value(length, "i32");
                }
                if (!array.type.equals("%ow.array"))
                    throw new RuntimeException("LLVM backend: len currently supports integer arrays and strings");
                String length = newTemp();
                body.append("  ").append(length).append(" = extractvalue %ow.array ")
                        .append(array.ref).append(", 0\n");
                if (array.owned) releaseArray(array);
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
                if (!fill.type.equals("i32"))
                    throw new RuntimeException("LLVM backend: native arrays currently store only integers");
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
                if (av.type.equals("%ow.array") && !av.owned) retainArray(av);
                if (i > 0) args.append(", ");
                args.append(expected).append(" ").append(av.ref);
            }
            String type = llvmType(signature.returnType);
            String t = newTemp();
            body.append("  ").append(t).append(" = call ").append(type).append(" @")
                    .append(call.name).append("(").append(args).append(")\n");
            return new Value(t, type, type.equals("%ow.array"));
        }
        throw new RuntimeException("Unknown expr: " + e.getClass());
    }

    /** Allocate zero-initialized storage for a native integer array.
     * calloc receives an element count and a four-byte element size. Even
     * empty arrays allocate one element to avoid implementation-defined
     * calloc(0,4) behavior. Allocation failure terminates rather than
     * performing a null dereference.
     */
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
        if (!array.type.equals("%ow.array"))
            throw new RuntimeException("LLVM backend: indexing requires an integer array");
        if (!index.type.equals("i32"))
            throw new RuntimeException("LLVM backend: array indices must be integers");
        String size = newTemp();
        body.append("  ").append(size).append(" = extractvalue %ow.array ")
                .append(array.ref).append(", 0\n");
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

    private void releaseAllArrayLocals() {
        // Deterministic order is not required: each slot owns one reference.
        for (Map.Entry<String, String> entry : localTypes.entrySet()) {
            if (entry.getValue().equals("%ow.array"))
                releaseArrayFromSlot(locals.get(entry.getKey()));
        }
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
        if (declaredType.equals("string")) {
            return "i8*";
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
