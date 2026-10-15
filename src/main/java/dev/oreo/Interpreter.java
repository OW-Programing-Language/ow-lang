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

    // ---- Runtime values for OOP ----

    public static final class OwEnumValue {
        public final String enumName;
        public final String variant;
        public final int ordinal;
        OwEnumValue(String enumName, String variant, int ordinal) {
            this.enumName = enumName;
            this.variant = variant;
            this.ordinal = ordinal;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof OwEnumValue e)) return false;
            return enumName.equals(e.enumName) && variant.equals(e.variant);
        }
        @Override public int hashCode() { return Objects.hash(enumName, variant); }
        @Override public String toString() { return variant; }
    }

    public static final class OwObject {
        public final Ast.ClassDecl cls;
        public final Map<String, Object> fields = new HashMap<>();
        OwObject(Ast.ClassDecl cls) { this.cls = cls; }
        @Override public String toString() { return "<" + cls.name + ">"; }
    }

    private final PrintStream out;
    private final InputProvider inputProvider;
    private final Map<String, Ast.FnDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.EnumDecl> enums = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Object> globals = new HashMap<>();

    private UiHost ui; // lazily created (web or native)

    public Interpreter(PrintStream out) {
        this(out, consoleInput(out));
    }

    public Interpreter(PrintStream out, InputProvider inputProvider) {
        this.out = out;
        this.inputProvider = inputProvider;
    }

    public PrintStream out() { return out; }

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
        for (Ast.EnumDecl e : program.enums) {
            if (enums.containsKey(e.name)) throw new RuntimeException("Duplicate enum: " + e.name);
            enums.put(e.name, e);
        }
        for (Ast.InterfaceDecl i : program.interfaces) {
            if (interfaces.containsKey(i.name)) throw new RuntimeException("Duplicate interface: " + i.name);
            interfaces.put(i.name, i);
        }
        for (Ast.ClassDecl c : program.classes) {
            if (classes.containsKey(c.name)) throw new RuntimeException("Duplicate class: " + c.name);
            classes.put(c.name, c);
        }
        try {
            execBlock(program.mainBody, globals);
        } finally {
            if (ui != null) ui.shutdown();
        }
    }

    /** Stops a running UI (if any), releasing a blocked ui_run call. Safe to call from any thread. */
    public void stopUi() {
        if (ui != null) ui.shutdown();
    }

    /** Public entry used by the UI runtime to invoke an Ow function by name. */
    public Object callFunction(String name, List<Object> args) {
        Ast.FnDecl fn = functions.get(name);
        if (fn == null) throw new RuntimeException("Unknown function: " + name);
        return invokeFunction(fn, args);
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
            Object value = evalExpr(set.value, env);
            if (set.typeName != null) checkType(set.typeName, value, "variable '" + set.name + "'");
            assign(set.name, value, env);
            return;
        }
        if (s instanceof Ast.SetIndexStmt set) {
            Object target = resolve(set.name, env);
            if (!(target instanceof List<?> list)) {
                throw new RuntimeException("Variable '" + set.name + "' is not an array");
            }
            int index = toIndex(evalExpr(set.index, env));
            checkBounds(index, list.size());
            ((List<Object>) list).set(index, evalExpr(set.value, env));
            return;
        }
        if (s instanceof Ast.SetFieldStmt set) {
            Object target = evalExpr(set.target, env);
            if (!(target instanceof OwObject obj)) {
                throw new RuntimeException("Cannot set field '" + set.field + "' on a non-object");
            }
            Ast.FieldDecl fd = findField(obj.cls, set.field);
            if (fd == null) {
                throw new RuntimeException("Class " + obj.cls.name + " has no field '" + set.field + "'");
            }
            if (fd.isPrivate && env.get("this") != obj) {
                throw new RuntimeException("Field '" + set.field + "' of " + obj.cls.name + " is private");
            }
            Object value = evalExpr(set.value, env);
            if (fd.typeName != null) checkType(fd.typeName, value, "field '" + set.field + "'");
            obj.fields.put(set.field, value);
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
        if (s instanceof Ast.SwitchStmt sw) {
            Object subject = evalExpr(sw.subject, env);
            for (Ast.CaseClause c : sw.cases) {
                for (Ast.Expr ve : c.values) {
                    if (valuesEqual(subject, evalExpr(ve, env))) {
                        execBlock(c.body, env);
                        return;
                    }
                }
            }
            if (sw.defaultBody != null) execBlock(sw.defaultBody, env);
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

    // ---- scope helpers (local env with global fallback) ----

    private void assign(String name, Object value, Map<String, Object> env) {
        if (env == globals) { globals.put(name, value); return; }
        if (env.containsKey(name)) { env.put(name, value); return; }
        if (globals.containsKey(name)) { globals.put(name, value); return; }
        env.put(name, value);
    }

    private Object resolve(String name, Map<String, Object> env) {
        if (env.containsKey(name)) return env.get(name);
        if (globals.containsKey(name)) return globals.get(name);
        throw new RuntimeException("Undefined variable: " + name);
    }

    private boolean isDefined(String name, Map<String, Object> env) {
        return env.containsKey(name) || globals.containsKey(name);
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
            if (!isDefined(v.name, env)) {
                throw new RuntimeException("Undefined variable: " + v.name);
            }
            return resolve(v.name, env);
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
        if (e instanceof Ast.FieldAccess fa) {
            // EnumName.Variant
            if (fa.target instanceof Ast.Var tv && enums.containsKey(tv.name) && !isDefined(tv.name, env)) {
                return enumValue(tv.name, fa.field);
            }
            Object target = evalExpr(fa.target, env);
            if (target instanceof OwObject obj) {
                Ast.FieldDecl fd = findField(obj.cls, fa.field);
                if (fd == null) {
                    throw new RuntimeException("Class " + obj.cls.name + " has no field '" + fa.field + "'");
                }
                if (fd.isPrivate && env.get("this") != obj) {
                    throw new RuntimeException("Field '" + fa.field + "' of " + obj.cls.name + " is private");
                }
                return obj.fields.get(fa.field);
            }
            throw new RuntimeException("Cannot read field '" + fa.field + "' of " + formatValue(target));
        }
        if (e instanceof Ast.NewExpr ne) {
            return instantiate(ne, env);
        }
        if (e instanceof Ast.MethodCall mc) {
            Object target = evalExpr(mc.target, env);
            if (!(target instanceof OwObject obj)) {
                throw new RuntimeException("Cannot call method '" + mc.method + "' on " + formatValue(target));
            }
            List<Object> args = new ArrayList<>();
            for (Ast.Expr a : mc.args) args.add(evalExpr(a, env));
            boolean external = env.get("this") != obj;
            return invokeMethod(obj, mc.method, args, external);
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
            List<Object> args = new ArrayList<>(call.args.size());
            for (Ast.Expr a : call.args) args.add(evalExpr(a, env));
            return invokeFunction(fn, args);
        }
        throw new RuntimeException("Unknown expression: " + e.getClass().getSimpleName());
    }

    private Object invokeFunction(Ast.FnDecl fn, List<Object> args) {
        if (fn.params.size() != args.size()) {
            throw new RuntimeException("Function " + fn.name + " expects "
                    + fn.params.size() + " args, got " + args.size());
        }
        Map<String, Object> local = new HashMap<>();
        for (int i = 0; i < fn.params.size(); i++) {
            local.put(fn.params.get(i), args.get(i));
        }
        try {
            execBlock(fn.body, local);
        } catch (ReturnSignal rs) {
            return rs.value;
        }
        return 0;
    }

    // ---- OOP runtime ----

    private OwEnumValue enumValue(String enumName, String variant) {
        Ast.EnumDecl ed = enums.get(enumName);
        int ord = ed.variants.indexOf(variant);
        if (ord < 0) throw new RuntimeException("Enum " + enumName + " has no variant '" + variant + "'");
        return new OwEnumValue(enumName, variant, ord);
    }

    private Object instantiate(Ast.NewExpr ne, Map<String, Object> env) {
        Ast.ClassDecl cls = classes.get(ne.className);
        if (cls == null) throw new RuntimeException("Unknown class: " + ne.className);
        OwObject obj = new OwObject(cls);
        // initialise fields (including inherited), base classes first
        for (Ast.ClassDecl c : classChain(cls)) {
            for (Ast.FieldDecl f : c.fields) {
                Object init = (f.defaultValue != null) ? evalExpr(f.defaultValue, globals) : 0;
                obj.fields.put(f.name, init);
            }
        }
        List<Object> args = new ArrayList<>();
        for (Ast.Expr a : ne.args) args.add(evalExpr(a, env));
        Ast.MethodDecl ctor = findMethod(cls, "new");
        if (ctor != null) {
            invokeMethodDecl(obj, ctor, args);
        } else if (!args.isEmpty()) {
            throw new RuntimeException("Class " + cls.name + " has no 'new' constructor but got "
                    + args.size() + " argument(s)");
        }
        return obj;
    }

    private Object invokeMethod(OwObject obj, String name, List<Object> args, boolean external) {
        Ast.MethodDecl m = findMethod(obj.cls, name);
        if (m == null) throw new RuntimeException("Class " + obj.cls.name + " has no method '" + name + "'");
        if (m.isPrivate && external) {
            throw new RuntimeException("Method '" + name + "' of " + obj.cls.name + " is private");
        }
        return invokeMethodDecl(obj, m, args);
    }

    private Object invokeMethodDecl(OwObject obj, Ast.MethodDecl m, List<Object> args) {
        if (m.params.size() != args.size()) {
            throw new RuntimeException("Method " + m.name + " expects "
                    + m.params.size() + " args, got " + args.size());
        }
        Map<String, Object> local = new HashMap<>();
        local.put("this", obj);
        for (int i = 0; i < m.params.size(); i++) {
            local.put(m.params.get(i), args.get(i));
        }
        try {
            execBlock(m.body, local);
        } catch (ReturnSignal rs) {
            return rs.value;
        }
        return 0;
    }

    /** The class and its superclass chain (interfaces are skipped — they carry no state/behaviour). */
    private List<Ast.ClassDecl> classChain(Ast.ClassDecl cls) {
        List<Ast.ClassDecl> chain = new ArrayList<>();
        Deque<Ast.ClassDecl> stack = new ArrayDeque<>();
        stack.push(cls);
        while (!stack.isEmpty()) {
            Ast.ClassDecl c = stack.pop();
            chain.add(0, c); // base first
            for (String sup : c.supers) {
                Ast.ClassDecl pc = classes.get(sup);
                if (pc != null) stack.push(pc);
            }
        }
        // de-duplicate while keeping base-first order
        LinkedHashSet<Ast.ClassDecl> seen = new LinkedHashSet<>(chain);
        return new ArrayList<>(seen);
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl cls, String name) {
        // most-derived wins: search cls first, then up the parents
        Ast.ClassDecl c = cls;
        List<Ast.ClassDecl> order = new ArrayList<>(classChain(cls));
        Collections.reverse(order); // derived first
        for (Ast.ClassDecl cd : order) {
            for (Ast.MethodDecl m : cd.methods) {
                if (m.name.equals(name)) return m;
            }
        }
        return null;
    }

    private Ast.FieldDecl findField(Ast.ClassDecl cls, String name) {
        List<Ast.ClassDecl> order = new ArrayList<>(classChain(cls));
        Collections.reverse(order);
        for (Ast.ClassDecl cd : order) {
            for (Ast.FieldDecl f : cd.fields) {
                if (f.name.equals(name)) return f;
            }
        }
        return null;
    }

    private boolean classMatchesType(Ast.ClassDecl cls, String typeName) {
        for (Ast.ClassDecl c : classChain(cls)) {
            if (c.name.equals(typeName)) return true;
            for (String sup : c.supers) if (sup.equals(typeName)) return true;
        }
        return false;
    }

    // ---- type checking for typed vars/fields ----

    private void checkType(String typeName, Object value, String what) {
        switch (typeName) {
            case "int" -> {
                if (!(value instanceof Integer) && !(value instanceof Boolean))
                    throw typeErr(typeName, value, what);
            }
            case "string", "str" -> {
                if (!(value instanceof String)) throw typeErr(typeName, value, what);
            }
            case "bool", "boolean" -> {
                if (!(value instanceof Boolean) && !(value instanceof Integer))
                    throw typeErr(typeName, value, what);
            }
            case "array", "list" -> {
                if (!(value instanceof List<?>)) throw typeErr(typeName, value, what);
            }
            default -> {
                if (enums.containsKey(typeName)) {
                    if (!(value instanceof OwEnumValue ev) || !ev.enumName.equals(typeName))
                        throw typeErr(typeName, value, what);
                } else if (classes.containsKey(typeName) || interfaces.containsKey(typeName)) {
                    if (!(value instanceof OwObject obj) || !classMatchesType(obj.cls, typeName))
                        throw typeErr(typeName, value, what);
                }
                // unknown type name: no runtime check
            }
        }
    }

    private RuntimeException typeErr(String typeName, Object value, String what) {
        return new RuntimeException("Type error: " + what + " expects " + typeName
                + " but got " + typeNameOf(value) + " (" + formatValue(value) + ")");
    }

    private String typeNameOf(Object v) {
        if (v instanceof Integer) return "int";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "bool";
        if (v instanceof List<?>) return "array";
        if (v instanceof OwEnumValue ev) return ev.enumName;
        if (v instanceof OwObject o) return o.cls.name;
        return String.valueOf(v == null ? "null" : v.getClass().getSimpleName());
    }

    // ---- builtins ----

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "input", "int", "len", "array", "str", "mod",
            "ui_text", "ui_heading", "ui_button", "ui_row", "ui_col",
            "ui_grid", "ui_disc", "ui_run", "ui_run_web", "ui_run_native", "ui_stop"
    ));

    private static boolean isBuiltin(String name) { return BUILTINS.contains(name); }

    private Object evalBuiltin(Ast.CallExpr call, Map<String, Object> env) {
        return switch (call.name) {
            case "input" -> builtinInput(call, env);
            case "int" -> builtinInt(call, env);
            case "str" -> formatValue(evalExpr(arg(call, 0, 1), env));
            case "len" -> builtinLen(call, env);
            case "array" -> builtinArray(call, env);
            case "mod" -> builtinMod(call, env);
            case "ui_text" -> uiNode("text", "text", str(call, 0, env));
            case "ui_heading" -> uiNode("heading", "text", str(call, 0, env));
            case "ui_button" -> builtinUiButton(call, env);
            case "ui_row" -> uiContainer("row", call, env);
            case "ui_col" -> uiContainer("col", call, env);
            case "ui_grid" -> builtinUiGrid(call, env);
            case "ui_disc" -> uiNode("disc", "color", str(call, 0, env));
            case "ui_run" -> builtinUiRun(call, env, null);
            case "ui_run_web" -> builtinUiRun(call, env, "web");
            case "ui_run_native" -> builtinUiRun(call, env, "native");
            case "ui_stop" -> { if (ui != null) ui.requestStop(); yield 0; }
            default -> throw new RuntimeException("Unknown builtin: " + call.name);
        };
    }

    private Ast.Expr arg(Ast.CallExpr call, int i, int expected) {
        if (call.args.size() != expected) {
            throw new RuntimeException(call.name + " expects " + expected + " args, got " + call.args.size());
        }
        return call.args.get(i);
    }

    private String str(Ast.CallExpr call, int i, Map<String, Object> env) {
        return formatValue(evalExpr(call.args.get(i), env));
    }

    private Object builtinInput(Ast.CallExpr call, Map<String, Object> env) {
        if (call.args.size() > 1) throw new RuntimeException("input expects 0 or 1 args");
        String prompt = "";
        if (call.args.size() == 1) prompt = formatValue(evalExpr(call.args.get(0), env));
        return inputProvider.readLine(prompt);
    }

    private Object builtinInt(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        Object value = evalExpr(call.args.get(0), env);
        if (value instanceof Integer i) return i;
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ex) { throw new RuntimeException("Cannot convert to int: " + s); }
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
        for (int i = 0; i < size; i++) list.add(cloneValue(fill));
        return list;
    }

    private Object builtinMod(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        int a = toInt(evalExpr(call.args.get(0), env));
        int b = toInt(evalExpr(call.args.get(1), env));
        if (b == 0) throw new RuntimeException("mod by zero");
        return a % b;
    }

    // ---- UI builtins → plain data nodes consumed by UiRuntime ----

    private Map<String, Object> uiNode(String type, String key, Object value) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put(key, value);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> uiContainer(String type, Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        Object children = evalExpr(call.args.get(0), env);
        if (!(children instanceof List<?>)) throw new RuntimeException(call.name + " expects an array of widgets");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("children", children);
        return node;
    }

    private Map<String, Object> builtinUiGrid(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        int cols = toInt(evalExpr(call.args.get(0), env));
        Object children = evalExpr(call.args.get(1), env);
        if (!(children instanceof List<?>)) throw new RuntimeException("ui_grid expects (cols, array)");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "grid");
        node.put("cols", cols);
        node.put("children", children);
        return node;
    }

    private Map<String, Object> builtinUiButton(Ast.CallExpr call, Map<String, Object> env) {
        if (call.args.size() < 2 || call.args.size() > 3) {
            throw new RuntimeException("ui_button expects (label, handler) or (label, handler, arg)");
        }
        String label = formatValue(evalExpr(call.args.get(0), env));
        Object handler = evalExpr(call.args.get(1), env);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "button");
        node.put("label", label);
        node.put("onClick", formatValue(handler));
        if (call.args.size() == 3) node.put("arg", evalExpr(call.args.get(2), env));
        return node;
    }

    private Object builtinUiRun(Ast.CallExpr call, Map<String, Object> env, String forcedMode) {
        if (call.args.size() < 1 || call.args.size() > 2) {
            throw new RuntimeException(call.name + " expects (renderFn) or (renderFn, title)");
        }
        String renderFn = formatValue(evalExpr(call.args.get(0), env));
        String title = call.args.size() == 2 ? formatValue(evalExpr(call.args.get(1), env)) : "Ow App";
        if (ui == null) {
            ui = resolveMode(forcedMode).equals("native")
                    ? new SwingUiRuntime(this, out)
                    : new UiRuntime(this, out);
        }
        ui.run(renderFn, title);
        return 0;
    }

    private static String resolveMode(String forced) {
        if (forced != null) return forced;
        String mode = System.getProperty("ow.ui.mode");
        if (mode == null) mode = System.getenv("OW_UI_MODE");
        return (mode != null && mode.equalsIgnoreCase("native")) ? "native" : "web";
    }

    private static void requireArgCount(Ast.CallExpr call, int expected) {
        if (call.args.size() != expected) {
            throw new RuntimeException("Function " + call.name + " expects "
                    + expected + " args, got " + call.args.size());
        }
    }

    // ---- value semantics ----

    private boolean valuesEqual(Object l, Object r) {
        if (l instanceof OwEnumValue || r instanceof OwEnumValue) return Objects.equals(l, r);
        if (l instanceof OwObject || r instanceof OwObject) return l == r;
        if (l instanceof List<?> || r instanceof List<?>) return Objects.equals(l, r);
        if (l instanceof String || r instanceof String) return formatValue(l).equals(formatValue(r));
        return toInt(l) == toInt(r);
    }

    private boolean compare(String op, Object l, Object r) {
        if (l instanceof OwEnumValue || r instanceof OwEnumValue) {
            return switch (op) {
                case "==" -> Objects.equals(l, r);
                case "!=" -> !Objects.equals(l, r);
                default -> throw new RuntimeException("Operator " + op + " not supported on enums");
            };
        }
        if (l instanceof OwObject || r instanceof OwObject) {
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                default -> throw new RuntimeException("Operator " + op + " not supported on objects");
            };
        }
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
        if (o instanceof OwEnumValue e) return e.ordinal;
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

    String formatValue(Object o) {
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (o instanceof OwObject obj) {
            Ast.MethodDecl ts = findMethod(obj.cls, "to_string");
            if (ts != null && ts.params.isEmpty()) {
                return formatValue(invokeMethodDecl(obj, ts, List.of()));
            }
            return obj.toString();
        }
        return String.valueOf(o);
    }
}
