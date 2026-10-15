package dev.oreo;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class LlvmIrGen {

    private static class Value {
        final String ref;
        final String type;
        Value(String ref, String type) { this.ref = ref; this.type = type; }
    }

    private final StringBuilder globals = new StringBuilder();
    private StringBuilder body;
    private int tempId;
    private int labelId;
    private int strCount;

    private final Set<String> functionNames = new HashSet<>();
    private Map<String, String> locals;
    private Map<String, String> localTypes;

    public String generate(Ast.Program program, String moduleName) {
        globals.setLength(0);
        strCount = 0;
        functionNames.clear();
        for (Ast.FnDecl fn : program.functions) {
            if (!functionNames.add(fn.name)) {
                throw new RuntimeException("Duplicate function: " + fn.name);
            }
        }

        StringBuilder fnsCode = new StringBuilder();
        for (Ast.FnDecl fn : program.functions) {
            fnsCode.append(emitFunction(fn));
        }
        fnsCode.append(emitMain(program.mainBody));

        StringBuilder out = new StringBuilder();
        out.append("; ModuleID = '").append(moduleName).append("'\n");
        out.append("declare i32 @printf(i8*, ...)\n");
        out.append("@.fmt_int = private constant [4 x i8] c\"%d\\0A\\00\"\n");
        out.append("@.fmt_str = private constant [4 x i8] c\"%s\\0A\\00\"\n");
        out.append(globals);
        out.append('\n');
        out.append(fnsCode);
        return out.toString();
    }

    private String emitFunction(Ast.FnDecl fn) {
        body = new StringBuilder();
        tempId = 0;
        labelId = 0;
        locals = new HashMap<>();
        localTypes = new HashMap<>();

        StringBuilder header = new StringBuilder();
        header.append("define i32 @").append(fn.name).append("(");
        for (int i = 0; i < fn.params.size(); i++) {
            if (i > 0) header.append(", ");
            header.append("i32 %").append(fn.params.get(i)).append(".arg");
        }
        header.append(") {\nentry:\n");

        for (String pname : fn.params) {
            String ptr = "%" + pname;
            locals.put(pname, ptr);
            localTypes.put(pname, "i32");
            body.append("  ").append(ptr).append(" = alloca i32\n");
            body.append("  store i32 %").append(pname).append(".arg, i32* ").append(ptr).append("\n");
        }

        for (Ast.Stmt s : fn.body) genStmt(s);
        body.append("  ret i32 0\n");
        body.append("}\n\n");
        return header.toString() + body.toString();
    }

    private String emitMain(List<Ast.Stmt> mainBody) {
        body = new StringBuilder();
        tempId = 0;
        labelId = 0;
        locals = new HashMap<>();
        localTypes = new HashMap<>();

        StringBuilder header = new StringBuilder();
        header.append("define i32 @main() {\nentry:\n");

        for (Ast.Stmt s : mainBody) genStmt(s);
        body.append("  ret i32 0\n");
        body.append("}\n");
        return header.toString() + body.toString();
    }

    private void genStmt(Ast.Stmt st) {
        if (st instanceof Ast.SetStmt s) {
            Value v = genExpr(s.value);
            String existing = locals.get(s.name);
            if (existing == null) {
                String ptr = "%" + s.name;
                locals.put(s.name, ptr);
                localTypes.put(s.name, v.type);
                body.append("  ").append(ptr).append(" = alloca ").append(v.type).append("\n");
                body.append("  store ").append(v.type).append(" ").append(v.ref)
                        .append(", ").append(v.type).append("* ").append(ptr).append("\n");
            } else {
                String knownType = localTypes.get(s.name);
                if (!knownType.equals(v.type)) {
                    throw new RuntimeException("Variable '" + s.name + "' was " + knownType
                            + " but assigned " + v.type);
                }
                body.append("  store ").append(v.type).append(" ").append(v.ref)
                        .append(", ").append(v.type).append("* ").append(existing).append("\n");
            }
            return;
        }

        if (st instanceof Ast.SetIndexStmt) {
            throw new RuntimeException("LLVM backend: array mutation is not supported yet");
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
            if (!v.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: return only supports integers");
            }
            body.append("  ret i32 ").append(v.ref).append("\n");
            String dead = "after.return." + (labelId++);
            body.append(dead).append(":\n");
            return;
        }

        if (st instanceof Ast.CallStmt c) {
            genExpr(c.call);
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
        if (e instanceof Ast.ArrayLit) {
            throw new RuntimeException("LLVM backend: arrays are not supported yet");
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
        if (e instanceof Ast.IndexExpr) {
            throw new RuntimeException("LLVM backend: array indexing is not supported yet");
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
            if (!l.type.equals("i32") || !r.type.equals("i32")) {
                throw new RuntimeException("LLVM backend: comparisons require integers");
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
            if (call.name.equals("input") || call.name.equals("int")
                    || call.name.equals("len") || call.name.equals("array")) {
                throw new RuntimeException("LLVM backend: builtin '" + call.name + "' is not supported yet");
            }
            if (!functionNames.contains(call.name)) {
                throw new RuntimeException("Unknown function: " + call.name);
            }
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < call.args.size(); i++) {
                Value av = genExpr(call.args.get(i));
                if (!av.type.equals("i32")) {
                    throw new RuntimeException("LLVM backend: function args must be integers");
                }
                if (i > 0) args.append(", ");
                args.append("i32 ").append(av.ref);
            }
            String t = newTemp();
            body.append("  ").append(t).append(" = call i32 @").append(call.name)
                    .append("(").append(args).append(")\n");
            return new Value(t, "i32");
        }
        throw new RuntimeException("Unknown expr: " + e.getClass());
    }

    private String emitStringLiteral(String s) {
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
