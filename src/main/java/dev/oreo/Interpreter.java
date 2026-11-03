package dev.oreo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.TargetDataLine;
import org.vosk.Model;
import org.vosk.Recognizer;

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

    /** A record / map value: {key: value, ...}, also the row type for the built-in DB. */
    public static final class OwRecord extends LinkedHashMap<String, Object> {}

    /** An explicit HTTP response with a status code (from the `response(...)` builtin). */
    public static final class OwResponse {
        public final int status;
        public final Object body;
        OwResponse(int status, Object body) { this.status = status; this.body = body; }
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

    // built-in database
    private final Map<String, Ast.ModelDecl> models = new HashMap<>();
    private final Map<String, List<OwRecord>> db = new LinkedHashMap<>();
    private boolean dbLoaded = false;

    private UiHost ui; // lazily created (web or native)
    private WebRuntime web; // lazily created
    private final ThreadLocal<OwRecord> currentRequest = new ThreadLocal<>();
    private final java.util.Random random = new java.util.Random();

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
        for (Ast.ModelDecl m : program.models) {
            if (models.containsKey(m.name)) throw new RuntimeException("Duplicate model: " + m.name);
            models.put(m.name, m);
            db.put(m.name, new ArrayList<>());
        }
        if (!models.isEmpty()) loadDb();
        if (program.mainBody == null) {
            throw new RuntimeException("Program is missing a 'main:' block");
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

    /**
     * Public entry used by the UI/web runtimes to invoke an Ow handler by name.
     * A dotted name like "game.move" dispatches to the method `move` on the global
     * object `game`, so UI handlers can target methods, not just free functions.
     */
    public Object callFunction(String name, List<Object> args) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            String base = name.substring(0, dot);
            String method = name.substring(dot + 1);
            Object target = globals.get(base);
            if (target instanceof OwObject obj) {
                return invokeMethod(obj, method, args, true);
            }
            throw new RuntimeException("Handler '" + name + "': '" + base + "' is not an object");
        }
        Ast.FnDecl fn = functions.get(name);
        if (fn == null) throw new RuntimeException("Unknown function: " + name);
        return invokeFunction(fn, args);
    }

    private static class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { super(null, null, false, false); this.value = value; }
    }

    private static class ResponseSignal extends RuntimeException {
        final Object value;
        ResponseSignal(Object value) { super(null, null, false, false); this.value = value; }
    }

    /** Runs a route's inline handler body with `req` bound; returns the responded value (or null). */
    public Object runRouteHandler(List<Ast.Stmt> body, OwRecord req) {
        Map<String, Object> env = new HashMap<>();
        env.put("req", req);
        currentRequest.set(req);
        try {
            execBlock(body, env);
            return null;
        } catch (ResponseSignal rs) {
            return rs.value;
        } finally {
            currentRequest.remove();
        }
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
            if (target instanceof OwRecord rec) {
                rec.put(set.field, evalExpr(set.value, env));
                return;
            }
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
        if (s instanceof Ast.TryStmt t) {
            try {
                execBlock(t.tryBody, env);
            } catch (ReturnSignal | ResponseSignal signal) {
                throw signal; // control flow, not an error — must not be caught
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                assign(t.catchName, msg == null ? "" : msg, env);
                execBlock(t.catchBody, env);
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
        if (s instanceof Ast.SaveStmt save) {
            saveRow(save, env);
            return;
        }
        if (s instanceof Ast.DeleteStmt del) {
            deleteRows(del, env);
            return;
        }
        if (s instanceof Ast.RouteStmt route) {
            if (web == null) web = new WebRuntime(this, out);
            web.register(route.spec, route.body);
            return;
        }
        if (s instanceof Ast.ServeStmt serve) {
            if (web == null) web = new WebRuntime(this, out);
            web.serve(toInt(evalExpr(serve.port, env)));
            return;
        }
        if (s instanceof Ast.RespondStmt resp) {
            throw new ResponseSignal(evalExpr(resp.value, env));
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
        // A `set` inside a function/method targets the local scope: declare it
        // there (or update it if already local). We must NOT fall through to a
        // same-named global, or a callee's local would clobber the caller's
        // variable (e.g. a loop counter named `i`), since top-level `main` runs
        // with env == globals. Reads still see globals via resolve().
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
        if (e instanceof Ast.FloatLit f) return f.value;
        if (e instanceof Ast.StringLit s) return s.value;
        if (e instanceof Ast.BoolLit b) return b.value;
        if (e instanceof Ast.ArrayLit a) {
            List<Object> values = new ArrayList<>(a.elements.size());
            for (Ast.Expr element : a.elements) values.add(evalExpr(element, env));
            return values;
        }
        if (e instanceof Ast.MapLit ml) {
            OwRecord rec = new OwRecord();
            for (int i = 0; i < ml.keys.size(); i++) {
                rec.put(ml.keys.get(i), evalExpr(ml.values.get(i), env));
            }
            return rec;
        }
        if (e instanceof Ast.FindExpr fe) {
            return find(fe, env);
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
            if (target instanceof OwRecord rec) {
                return rec.get(fa.field);
            }
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
            // If either side is a float, compute in floating point (so `/`
            // gives a real quotient). Two integers keep integer arithmetic —
            // including integer division — so existing programs are unchanged.
            if (l instanceof Double || r instanceof Double) {
                double ld = toDouble(l);
                double rd = toDouble(r);
                return switch (b.op) {
                    case "+" -> ld + rd;
                    case "-" -> ld - rd;
                    case "*" -> ld * rd;
                    case "/" -> {
                        if (rd == 0.0) throw new RuntimeException("Division by zero");
                        yield ld / rd;
                    }
                    default -> throw new RuntimeException("Unknown operator: " + b.op);
                };
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

    // ---- built-in database ----

    private List<OwRecord> rowsOf(String model) {
        List<OwRecord> rows = db.get(model);
        if (rows == null) throw new RuntimeException("Unknown model: " + model);
        return rows;
    }

    private void saveRow(Ast.SaveStmt save, Map<String, Object> env) {
        if (!models.containsKey(save.model)) throw new RuntimeException("Unknown model: " + save.model);
        Object value = evalExpr(save.record, env);
        if (!(value instanceof Map)) {
            throw new RuntimeException("save " + save.model + ": expects a record like {field: value, ...}");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> src = (Map<String, Object>) value;
        OwRecord row = new OwRecord();
        row.putAll(src);

        // light type validation against the model's declared fields
        Ast.ModelDecl md = models.get(save.model);
        for (Ast.FieldDecl f : md.fields) {
            if (f.typeName != null && row.containsKey(f.name) && row.get(f.name) != null) {
                checkType(f.typeName, row.get(f.name), "field '" + f.name + "' of " + save.model);
            }
        }

        List<OwRecord> rows = rowsOf(save.model);
        if (row.containsKey("id")) {
            // upsert: replace an existing row with the same id, else append
            Object id = row.get("id");
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (valuesEqual(rows.get(i).get("id"), id)) { at = i; break; }
            }
            if (at >= 0) rows.set(at, row); else rows.add(row);
        } else {
            row.put("id", nextId(rows));
            rows.add(row);
        }
        persist();
    }

    private int nextId(List<OwRecord> rows) {
        int max = 0;
        for (OwRecord r : rows) {
            Object id = r.get("id");
            if (id instanceof Integer i && i > max) max = i;
        }
        return max + 1;
    }

    private void deleteRows(Ast.DeleteStmt del, Map<String, Object> env) {
        List<OwRecord> rows = rowsOf(del.model);
        if (del.where == null) {
            rows.clear();
        } else {
            rows.removeIf(row -> truthy(evalExpr(del.where, rowEnv(row, env))));
        }
        persist();
    }

    private Object find(Ast.FindExpr fe, Map<String, Object> env) {
        List<OwRecord> rows = rowsOf(fe.model);
        List<Object> result = new ArrayList<>();
        for (OwRecord row : rows) {
            if (fe.where == null || truthy(evalExpr(fe.where, rowEnv(row, env)))) {
                result.add(row);
            }
        }
        return result;
    }

    /** Query evaluation scope: caller's locals, with the row's fields shadowing on top. */
    private Map<String, Object> rowEnv(OwRecord row, Map<String, Object> callerEnv) {
        Map<String, Object> e = new HashMap<>(callerEnv);
        e.putAll(row);
        return e;
    }

    private java.nio.file.Path dbPath() {
        String p = System.getProperty("ow.db.path");
        if (p == null) p = System.getenv("OW_DB_PATH");
        if (p == null) p = "ow-data.json";
        return java.nio.file.Path.of(p);
    }

    @SuppressWarnings("unchecked")
    private void loadDb() {
        if (dbLoaded) return;
        dbLoaded = true;
        java.nio.file.Path path = dbPath();
        if (!java.nio.file.Files.exists(path)) return;
        try {
            String text = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (parsed instanceof Map<?, ?> top) {
                for (Map.Entry<?, ?> e : top.entrySet()) {
                    String model = String.valueOf(e.getKey());
                    if (!db.containsKey(model)) continue; // ignore models not declared this run
                    if (e.getValue() instanceof List<?> list) {
                        List<OwRecord> rows = db.get(model);
                        rows.clear();
                        for (Object o : list) if (o instanceof OwRecord r) rows.add(r);
                    }
                }
            }
        } catch (Exception ex) {
            out.println("[db] could not load " + path + ": " + ex.getMessage());
        }
    }

    private void persist() {
        OwRecord top = new OwRecord();
        for (Map.Entry<String, List<OwRecord>> e : db.entrySet()) {
            top.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        }
        try {
            java.nio.file.Files.writeString(dbPath(), Json.write(top), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            out.println("[db] could not save " + dbPath() + ": " + ex.getMessage());
        }
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
            "input", "int", "len", "array", "str", "mod", "rand", "time_ms",
            "json", "json_parse", "param", "query", "body", "body_json", "response",
            "ui_text", "ui_heading", "ui_button", "ui_row", "ui_col",
            "ui_grid", "ui_disc", "ui_image", "ui_input",
            "ui_run", "ui_run_web", "ui_run_native", "ui_stop",
            "audio_play", "audio_music", "audio_stop", "mic_level", "mic_transcribe",
            "save", "load", "exists",
            "upper", "lower", "trim", "substr", "index_of", "contains",
            "replace", "split", "char_at",
            "abs", "min", "max", "pow", "sqrt", "clamp",
            "floor", "round", "ceil",
            "sleep", "now_ms",
            "assert"
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
            case "rand" -> builtinRand(call, env);
            case "time_ms" -> { requireArgCount(call, 0); yield (int) (System.currentTimeMillis() % 1000000000L); }
            case "json" -> { requireArgCount(call, 1); yield Json.write(evalExpr(call.args.get(0), env)); }
            case "json_parse" -> { requireArgCount(call, 1); yield Json.parse(formatValue(evalExpr(call.args.get(0), env))); }
            case "param" -> reqSub("params", str(call, 0, env));
            case "query" -> reqSub("query", str(call, 0, env));
            case "body" -> { requireArgCount(call, 0); yield reqField("body"); }
            case "body_json" -> { requireArgCount(call, 0); yield Json.parse(String.valueOf(reqField("body"))); }
            case "response" -> { requireArgCount(call, 2); yield new OwResponse(
                    toInt(evalExpr(call.args.get(0), env)), evalExpr(call.args.get(1), env)); }
            case "ui_text" -> uiNode("text", "text", str(call, 0, env));
            case "ui_heading" -> uiNode("heading", "text", str(call, 0, env));
            case "ui_button" -> builtinUiButton(call, env);
            case "ui_row" -> uiContainer("row", call, env);
            case "ui_col" -> uiContainer("col", call, env);
            case "ui_grid" -> builtinUiGrid(call, env);
            case "ui_disc" -> uiNode("disc", "color", str(call, 0, env));
            case "ui_image" -> { requireArgCount(call, 1); yield uiNode("image", "src", str(call, 0, env)); }
            case "ui_input" -> builtinUiInput(call, env);
            case "ui_run" -> builtinUiRun(call, env, null);
            case "ui_run_web" -> builtinUiRun(call, env, "web");
            case "ui_run_native" -> builtinUiRun(call, env, "native");
            case "ui_stop" -> { if (ui != null) ui.requestStop(); yield 0; }
            case "audio_play" -> builtinAudioPlay(call, env);
            case "audio_music" -> builtinAudioMusic(call, env);
            case "audio_stop" -> builtinAudioStop(call);
            case "mic_level" -> builtinMicLevel(call, env);
            case "mic_transcribe" -> builtinMicTranscribe(call, env);
            case "save" -> builtinSave(call, env);
            case "load" -> builtinLoad(call, env);
            case "exists" -> builtinExists(call, env);
            case "upper" -> { requireArgCount(call, 1); yield str(call, 0, env).toUpperCase(); }
            case "lower" -> { requireArgCount(call, 1); yield str(call, 0, env).toLowerCase(); }
            case "trim" -> { requireArgCount(call, 1); yield str(call, 0, env).trim(); }
            case "substr" -> builtinSubstr(call, env);
            case "index_of" -> { requireArgCount(call, 2); yield str(call, 0, env).indexOf(str(call, 1, env)); }
            case "contains" -> { requireArgCount(call, 2); yield str(call, 0, env).contains(str(call, 1, env)) ? 1 : 0; }
            case "replace" -> { requireArgCount(call, 3); yield str(call, 0, env).replace(str(call, 1, env), str(call, 2, env)); }
            case "split" -> builtinSplit(call, env);
            case "char_at" -> builtinCharAt(call, env);
            case "abs" -> { requireArgCount(call, 1); int n = toInt(evalExpr(call.args.get(0), env)); yield n < 0 ? -n : n; }
            case "min" -> { requireArgCount(call, 2); int a = toInt(evalExpr(call.args.get(0), env)); int b = toInt(evalExpr(call.args.get(1), env)); yield a < b ? a : b; }
            case "max" -> { requireArgCount(call, 2); int a = toInt(evalExpr(call.args.get(0), env)); int b = toInt(evalExpr(call.args.get(1), env)); yield a > b ? a : b; }
            case "pow" -> builtinPow(call, env);
            case "sqrt" -> builtinSqrt(call, env);
            case "clamp" -> builtinClamp(call, env);
            case "floor" -> { requireArgCount(call, 1); yield (int) Math.floor(toDouble(evalExpr(call.args.get(0), env))); }
            case "ceil" -> { requireArgCount(call, 1); yield (int) Math.ceil(toDouble(evalExpr(call.args.get(0), env))); }
            case "round" -> { requireArgCount(call, 1); yield (int) Math.round(toDouble(evalExpr(call.args.get(0), env))); }
            case "sleep" -> builtinSleep(call, env);
            case "now_ms" -> { requireArgCount(call, 0); yield (int) (System.currentTimeMillis() - startMillis); }
            case "assert" -> builtinAssert(call, env);
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
        if (value instanceof Double d) return (int) (double) d; // truncate toward zero
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ex) {
                try { return (int) Double.parseDouble(s.trim()); }
                catch (NumberFormatException ex2) { throw new RuntimeException("Cannot convert to int: " + s); }
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
        for (int i = 0; i < size; i++) list.add(cloneValue(fill));
        return list;
    }

    private Object reqField(String name) {
        OwRecord req = currentRequest.get();
        if (req == null) throw new RuntimeException("request builtins can only be used inside a route handler");
        return req.get(name);
    }

    private Object reqSub(String section, String key) {
        OwRecord req = currentRequest.get();
        if (req == null) throw new RuntimeException("request builtins can only be used inside a route handler");
        Object sub = req.get(section);
        return (sub instanceof OwRecord r) ? r.get(key) : null;
    }

    private Object builtinRand(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        int n = toInt(evalExpr(call.args.get(0), env));
        if (n <= 0) throw new RuntimeException("rand(n) expects n > 0");
        return random.nextInt(n);
    }

    private Object builtinMod(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        int a = toInt(evalExpr(call.args.get(0), env));
        int b = toInt(evalExpr(call.args.get(1), env));
        if (b == 0) throw new RuntimeException("mod by zero");
        return a % b;
    }

    // ---- file persistence builtins (JSON on disk) ----

    // save(path, value): write `value` to `path` as JSON. Creates parent
    // directories as needed. Returns 0.
    private Object builtinSave(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        File file = mediaFile(str(call, 0, env));
        Object value = evalExpr(call.args.get(1), env);
        try {
            File parent = file.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            Files.writeString(file.toPath(), Json.write(value));
        } catch (IOException e) {
            throw new RuntimeException("save: cannot write '" + file + "': " + e.getMessage());
        }
        return 0;
    }

    // load(path): read `path` and return the parsed JSON value. Returns 0
    // when the file does not exist (use exists(path) to distinguish).
    private Object builtinLoad(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        File file = mediaFile(str(call, 0, env));
        if (!file.isFile()) return 0;
        try {
            return Json.parse(Files.readString(file.toPath()));
        } catch (IOException e) {
            throw new RuntimeException("load: cannot read '" + file + "': " + e.getMessage());
        }
    }

    // exists(path): 1 if the file exists, 0 otherwise.
    private Object builtinExists(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        return mediaFile(str(call, 0, env)).isFile() ? 1 : 0;
    }

    // ---- string builtins ----

    // substr(s, start, len): substring with clamped, exception-free bounds.
    private Object builtinSubstr(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 3);
        String s = str(call, 0, env);
        int start = toInt(evalExpr(call.args.get(1), env));
        int len = toInt(evalExpr(call.args.get(2), env));
        if (start < 0) start = 0;
        if (start > s.length()) start = s.length();
        if (len < 0) len = 0;
        int end = start + len;
        if (end > s.length()) end = s.length();
        return s.substring(start, end);
    }

    // split(s, sep): split on a literal separator into an array of strings.
    // Empty separator splits into individual characters. Empty fields are kept.
    private Object builtinSplit(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        String s = str(call, 0, env);
        String sep = str(call, 1, env);
        List<Object> out = new ArrayList<>();
        if (sep.isEmpty()) {
            for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
            return out;
        }
        int i = 0;
        while (true) {
            int j = s.indexOf(sep, i);
            if (j < 0) { out.add(s.substring(i)); break; }
            out.add(s.substring(i, j));
            i = j + sep.length();
        }
        return out;
    }

    // char_at(s, i): the character at index i as a 1-char string, "" if out of range.
    private Object builtinCharAt(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        String s = str(call, 0, env);
        int i = toInt(evalExpr(call.args.get(1), env));
        if (i < 0 || i >= s.length()) return "";
        return String.valueOf(s.charAt(i));
    }

    // ---- math builtins (integer semantics) ----

    // pow(base, exp): integer base^exp for exp >= 0.
    private Object builtinPow(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        int base = toInt(evalExpr(call.args.get(0), env));
        int exp = toInt(evalExpr(call.args.get(1), env));
        if (exp < 0) throw new RuntimeException("pow(base, exp) expects exp >= 0");
        int r = 1;
        for (int i = 0; i < exp; i++) r = r * base;
        return r;
    }

    // sqrt(n): integer floor square root (largest r with r*r <= n), n >= 0.
    private Object builtinSqrt(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        int n = toInt(evalExpr(call.args.get(0), env));
        if (n < 0) throw new RuntimeException("sqrt(n) expects n >= 0");
        int r = 0;
        while ((r + 1) * (r + 1) <= n) r++;
        return r;
    }

    // clamp(n, lo, hi): n constrained to [lo, hi].
    private Object builtinClamp(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 3);
        int n = toInt(evalExpr(call.args.get(0), env));
        int lo = toInt(evalExpr(call.args.get(1), env));
        int hi = toInt(evalExpr(call.args.get(2), env));
        if (lo > hi) throw new RuntimeException("clamp(n, lo, hi) expects lo <= hi");
        if (n < lo) return lo;
        if (n > hi) return hi;
        return n;
    }

    // ---- timing builtins ----

    /** Wall-clock millis at interpreter start; now_ms() counts from here. */
    private final long startMillis = System.currentTimeMillis();

    // sleep(ms): block for ms milliseconds (ms <= 0 is a no-op). Returns 0.
    private Object builtinSleep(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        int ms = toInt(evalExpr(call.args.get(0), env));
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("sleep interrupted");
            }
        }
        return 0;
    }

    // assert(cond) / assert(cond, message): pass (return 0) when cond is
    // truthy; otherwise print "Assertion failed[: message]" to stderr and
    // exit with a non-zero status so test runners/CI see the failure.
    private Object builtinAssert(Ast.CallExpr call, Map<String, Object> env) {
        if (call.args.isEmpty() || call.args.size() > 2) {
            throw new RuntimeException("assert expects (cond) or (cond, message)");
        }
        if (truthy(evalExpr(call.args.get(0), env))) return 0;
        String message = call.args.size() == 2 ? str(call, 1, env) : "";
        System.err.println(message.isEmpty() ? "Assertion failed" : "Assertion failed: " + message);
        System.exit(1);
        return 0; // unreachable
    }

    // ---- audio & microphone builtins (pure JDK javax.sound.sampled) ----

    /** Background music clip, looped until audio_stop() or replaced. */
    private Clip musicClip;

    /** Resolve a possibly-relative media path against the process working dir. */
    private File mediaFile(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(System.getProperty("user.dir"), path);
    }

    private Clip openClip(String path) {
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(mediaFile(path));
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            return clip;
        } catch (Exception e) {
            throw new RuntimeException("audio: cannot open '" + path + "': " + e.getMessage());
        }
    }

    // audio_play(path): play a sound once (fire-and-forget), freeing it when done.
    private Object builtinAudioPlay(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        Clip clip = openClip(str(call, 0, env));
        clip.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) ev.getLine().close(); });
        clip.start();
        return 0;
    }

    // audio_music(path): loop as background music, replacing any current track.
    private Object builtinAudioMusic(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        stopMusic();
        Clip clip = openClip(str(call, 0, env));
        clip.loop(Clip.LOOP_CONTINUOUSLY);
        musicClip = clip;
        return 0;
    }

    // audio_stop(): stop the background music started by audio_music.
    private Object builtinAudioStop(Ast.CallExpr call) {
        requireArgCount(call, 0);
        stopMusic();
        return 0;
    }

    private void stopMusic() {
        if (musicClip != null) {
            musicClip.stop();
            musicClip.close();
            musicClip = null;
        }
    }

    // mic_level(ms): record from the default microphone for `ms` milliseconds and
    // return the peak amplitude as an int 0..100. This is a raw loudness reading
    // (enough for a "speak to continue" mechanic); it is NOT speech recognition —
    // turning audio into words needs an external engine (see docs).
    private Object builtinMicLevel(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 1);
        int ms = toInt(evalExpr(call.args.get(0), env));
        if (ms <= 0) throw new RuntimeException("mic_level(ms) expects ms > 0");
        AudioFormat format = new AudioFormat(16000f, 16, 1, true, false); // 16kHz mono PCM, little-endian
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("mic_level: no microphone line available");
        }
        TargetDataLine line = null;
        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + ms;
            int peak = 0;
            while (System.currentTimeMillis() < deadline) {
                int n = line.read(buf, 0, buf.length);
                for (int i = 0; i + 1 < n; i += 2) {
                    int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    int amp = Math.abs(sample);
                    if (amp > peak) peak = amp;
                }
            }
            return (int) Math.round(peak * 100.0 / 32767.0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("mic_level: " + e.getMessage());
        } finally {
            if (line != null) { line.stop(); line.close(); }
        }
    }

    /** Cache of loaded Vosk models, keyed by model directory path. */
    private final Map<String, Model> voskModels = new HashMap<>();

    private Model voskModel(String modelPath) {
        return voskModels.computeIfAbsent(modelPath, p -> {
            File dir = mediaFile(p);
            if (!dir.isDirectory()) {
                throw new RuntimeException("mic_transcribe: model not found at '" + p
                        + "'. Download a Vosk model (https://alphacephei.com/vosk/models) and unzip it there.");
            }
            try {
                return new Model(dir.getAbsolutePath());
            } catch (Exception e) {
                throw new RuntimeException("mic_transcribe: failed to load model '" + p + "': " + e.getMessage());
            }
        });
    }

    // mic_transcribe(model_path, ms): record from the default microphone for `ms`
    // milliseconds and return the recognized speech as lowercased text, using the
    // offline Vosk model in the directory `model_path`. Returns "" if nothing is
    // recognized. Unlike mic_level (loudness), this turns speech into words.
    private Object builtinMicTranscribe(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        String modelPath = str(call, 0, env);
        int ms = toInt(evalExpr(call.args.get(1), env));
        if (ms <= 0) throw new RuntimeException("mic_transcribe(model, ms) expects ms > 0");
        Model model = voskModel(modelPath);
        AudioFormat format = new AudioFormat(16000f, 16, 1, true, false); // 16kHz mono PCM, little-endian
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("mic_transcribe: no microphone line available");
        }
        TargetDataLine line = null;
        try (Recognizer recognizer = new Recognizer(model, 16000f)) {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < deadline) {
                int n = line.read(buf, 0, buf.length);
                if (n > 0) recognizer.acceptWaveForm(buf, n);
            }
            Object parsed = Json.parse(recognizer.getFinalResult());
            String text = (parsed instanceof OwRecord r && r.get("text") != null)
                    ? String.valueOf(r.get("text")) : "";
            return text.trim().toLowerCase();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("mic_transcribe: " + e.getMessage());
        } finally {
            if (line != null) { line.stop(); line.close(); }
        }
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

    // ui_input(value, handler): a text field pre-filled with `value` that calls
    // `handler` with the current text (as a string) when the user submits it.
    private Map<String, Object> builtinUiInput(Ast.CallExpr call, Map<String, Object> env) {
        requireArgCount(call, 2);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "input");
        node.put("value", formatValue(evalExpr(call.args.get(0), env)));
        node.put("onInput", formatValue(evalExpr(call.args.get(1), env)));
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
        if (l instanceof Double || r instanceof Double) return toDouble(l) == toDouble(r);
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
        if (l instanceof Double || r instanceof Double) {
            double ld = toDouble(l);
            double rd = toDouble(r);
            return switch (op) {
                case "==" -> ld == rd;
                case "!=" -> ld != rd;
                case "<"  -> ld <  rd;
                case ">"  -> ld >  rd;
                case "<=" -> ld <= rd;
                case ">=" -> ld >= rd;
                default -> throw new RuntimeException("Unknown comparison: " + op);
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

    private static double toDouble(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof Integer i) return i;
        if (o instanceof Boolean b) return b ? 1 : 0;
        throw new RuntimeException("Expected number, got: " + o);
    }

    /** Render a double without a trailing ".0" when it is integer-valued. */
    static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
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
        if (o instanceof OwRecord rec) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : rec.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append(": ").append(formatValue(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
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
        if (o instanceof Double d) return formatDouble(d);
        return String.valueOf(o);
    }
}
