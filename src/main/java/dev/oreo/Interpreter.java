package dev.oreo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Interpreter {

    @FunctionalInterface
    public interface InputProvider {
        String readLine(String prompt);
    }

    private final PrintStream out;
    private final InputProvider inputProvider;
    private final Map<String, Ast.FnDecl> functions = new HashMap<>();

    public Interpreter(PrintStream out) {
        this(out, consoleInput(out));
    }

    public Interpreter(PrintStream out, InputProvider inputProvider) {
        this.out = out;
        this.inputProvider = inputProvider;
    }

    private static InputProvider consoleInput(PrintStream out) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return prompt -> {
            out.print(prompt);
            out.flush();
            try {
                String line = in.readLine();
                return line != null ? line : "";
            } catch (IOException e) {
                throw new RuntimeException("Failed to read input", e);
            }
        };
    }

    public void run(Ast.Program program) {
        for (Ast.FnDecl fn : program.functions) {
            if (functions.containsKey(fn.name)) {
                throw new RuntimeException("Duplicate function: " + fn.name);
            }
            functions.put(fn.name, fn);
        }
        Map<String, Object> env = new HashMap<>();
        execBlock(program.mainBody, env);
    }

    private static class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { super(null, null, false, false); this.value = value; }
    }

    private void execBlock(List<Ast.Stmt> body, Map<String, Object> env) {
        for (Ast.Stmt s : body) execStmt(s, env);
    }

    private void execStmt(Ast.Stmt s, Map<String, Object> env) {
        if (s instanceof Ast.SetStmt set) {
            env.put(set.name, evalExpr(set.value, env));
            return;
        }
        if (s instanceof Ast.SetIndexStmt set) {
            Object target = env.get(set.name);
            if (!(target instanceof List<?> list)) {
                if (!env.containsKey(set.name)) {
                    throw new RuntimeException("Undefined variable: " + set.name);
                }
                throw new RuntimeException("Variable '" + set.name + "' is not an array");
            }
            int index = toIndex(evalExpr(set.index, env));
            checkBounds(index, list.size());
            ((List<Object>) list).set(index, evalExpr(set.value, env));
            return;
        }
        if (s instanceof Ast.PrintStmt p) {
            out.println(formatValue(evalExpr(p.value, env)));
            return;
        }
        if (s instanceof Ast.IfStmt iff) {
            if (truthy(evalExpr(iff.condition, env))) {
                execBlock(iff.thenBody, env);
            } else if (iff.elseBody != null) {
                execBlock(iff.elseBody, env);
            }
            return;
        }
        if (s instanceof Ast.WhileStmt w) {
            while (truthy(evalExpr(w.condition, env))) {
                execBlock(w.body, env);
            }
            return;
        }
        if (s instanceof Ast.ReturnStmt r) {
            throw new ReturnSignal(evalExpr(r.value, env));
        }
        if (s instanceof Ast.CallStmt c) {
            evalExpr(c.call, env);
            return;
        }
        throw new RuntimeException("Unknown statement: " + s.getClass().getSimpleName());
    }

    private Object evalExpr(Ast.Expr e, Map<String, Object> env) {
        if (e instanceof Ast.IntLit i) return i.value;
        if (e instanceof Ast.StringLit s) return s.value;
        if (e instanceof Ast.ArrayLit a) {
            List<Object> values = new ArrayList<>(a.elements.size());
            for (Ast.Expr element : a.elements) values.add(evalExpr(element, env));
            return values;
        }
        if (e instanceof Ast.Var v) {
            if (!env.containsKey(v.name)) {
                throw new RuntimeException("Undefined variable: " + v.name);
            }
            return env.get(v.name);
        }
        if (e instanceof Ast.IndexExpr idx) {
            Object target = evalExpr(idx.target, env);
            int index = toIndex(evalExpr(idx.index, env));
            if (!(target instanceof List<?> list)) {
                throw new RuntimeException("Indexing requires an array");
            }
            checkBounds(index, list.size());
            return list.get(index);
        }
        if (e instanceof Ast.BinOp b) {
            Object l = evalExpr(b.left, env);
            Object r = evalExpr(b.right, env);
            if (b.op.equals("+") && (l instanceof String || r instanceof String)) {
                return formatValue(l) + formatValue(r);
            }
            int li = toInt(l);
            int ri = toInt(r);
            return switch (b.op) {
                case "+" -> li + ri;
                case "-" -> li - ri;
                case "*" -> li * ri;
                case "/" -> {
                    if (ri == 0) throw new RuntimeException("Division by zero");
                    yield li / ri;
                }
                default -> throw new RuntimeException("Unknown operator: " + b.op);
            };
        }
        if (e instanceof Ast.Compare c) {
            Object l = evalExpr(c.left, env);
            Object r = evalExpr(c.right, env);
            return compare(c.op, l, r) ? 1 : 0;
        }
        if (e instanceof Ast.CallExpr call) {
            if (isBuiltin(call.name)) {
                return evalBuiltin(call, env);
            }
            Ast.FnDecl fn = functions.get(call.name);
            if (fn == null) throw new RuntimeException("Unknown function: " + call.name);
            if (fn.params.size() != call.args.size()) {
                throw new RuntimeException("Function " + call.name + " expects "
                        + fn.params.size() + " args, got " + call.args.size());
            }
            Map<String, Object> local = new HashMap<>();
            for (int i = 0; i < fn.params.size(); i++) {
                local.put(fn.params.get(i), evalExpr(call.args.get(i), env));
            }
            try {
                execBlock(fn.body, local);
            } catch (ReturnSignal rs) {
                return rs.value;
            }
            return 0;
        }
        throw new RuntimeException("Unknown expression: " + e.getClass().getSimpleName());
    }

    private Object evalBuiltin(Ast.CallExpr call, Map<String, Object> env) {
        return switch (call.name) {
            case "input" -> builtinInput(call, env);
            case "int" -> builtinInt(call, env);
            case "len" -> builtinLen(call, env);
            case "array" -> builtinArray(call, env);
            default -> throw new RuntimeException("Unknown builtin: " + call.name);
        };
    }

    private Object builtinInput(Ast.CallExpr call, Map<String, Object> env) {
        if (call.args.size() > 1) {
            throw new RuntimeException("input expects 0 or 1 args");
        }
        String prompt = "";
        if (call.args.size() == 1) {
            prompt = formatValue(evalExpr(call.args.get(0), env));
        }
        return inputProvider.readLine(prompt);
    }

    private Object builtinInt(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        Object value = evalExpr(call.args.get(0), env);
        if (value instanceof Integer i) return i;
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new RuntimeException("Cannot convert to int: " + s);
            }
        }
        throw new RuntimeException("int(...) expects a number, boolean, or string");
    }

    private Object builtinLen(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        Object value = evalExpr(call.args.get(0), env);
        if (value instanceof List<?> list) return list.size();
        if (value instanceof String s) return s.length();
        throw new RuntimeException("len(...) expects an array or string");
    }

    private Object builtinArray(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        int size = toIndex(evalExpr(call.args.get(0), env));
        Object fill = evalExpr(call.args.get(1), env);
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(cloneValue(fill));
        }
        return list;
    }

    private static void requireArgCount(Ast.CallExpr call, int expected) {
        if (call.args.size() != expected) {
            throw new RuntimeException("Function " + call.name + " expects "
                    + expected + " args, got " + call.args.size());
        }
    }

    private static boolean isBuiltin(String name) {
        return name.equals("input") || name.equals("int") || name.equals("len") || name.equals("array");
    }

    private static boolean compare(String op, Object l, Object r) {
        if (l instanceof List<?> || r instanceof List<?>) {
            return switch (op) {
                case "==" -> Objects.equals(l, r);
                case "!=" -> !Objects.equals(l, r);
                default -> throw new RuntimeException("Operator " + op + " not supported on arrays");
            };
        }
        if (l instanceof String || r instanceof String) {
            String ls = formatValue(l);
            String rs = formatValue(r);
            return switch (op) {
                case "==" -> ls.equals(rs);
                case "!=" -> !ls.equals(rs);
                default -> throw new RuntimeException("Operator " + op + " not supported on strings");
            };
        }
        int li = toInt(l);
        int ri = toInt(r);
        return switch (op) {
            case "==" -> li == ri;
            case "!=" -> li != ri;
            case "<"  -> li <  ri;
            case ">"  -> li >  ri;
            case "<=" -> li <= ri;
            case ">=" -> li >= ri;
            default -> throw new RuntimeException("Unknown comparison: " + op);
        };
    }

    private static boolean truthy(Object o) {
        if (o instanceof Integer i) return i != 0;
        if (o instanceof String s) return !s.isEmpty();
        if (o instanceof List<?> list) return !list.isEmpty();
        if (o instanceof Boolean b) return b;
        return o != null;
    }

    private static int toInt(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Boolean b) return b ? 1 : 0;
        throw new RuntimeException("Expected number, got: " + o);
    }

    private static int toIndex(Object o) {
        int index = toInt(o);
        if (index < 0) throw new RuntimeException("Array index cannot be negative: " + index);
        return index;
    }

    private static void checkBounds(int index, int size) {
        if (index < 0 || index >= size) {
            throw new RuntimeException("Array index out of bounds: " + index + " (size " + size + ")");
        }
    }

    private static Object cloneValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(cloneValue(item));
            return copy;
        }
        return value;
    }

    private static String formatValue(Object o) {
        if (o instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(formatValue(list.get(i)));
            }
            out.append("]");
            return out.toString();
        }
        return String.valueOf(o);
    }
}
