package dev.oreo;

import java.util.*;

public class Ast {

    public static class Program {
        public final List<String> imports;
        public final List<FnDecl> functions;
        public final List<EnumDecl> enums;
        public final List<InterfaceDecl> interfaces;
        public final List<ClassDecl> classes;
        public final List<ModelDecl> models;
        public final List<Stmt> mainBody; // may be null in imported (non-entry) files

        public Program(List<String> imports,
                       List<FnDecl> functions,
                       List<EnumDecl> enums,
                       List<InterfaceDecl> interfaces,
                       List<ClassDecl> classes,
                       List<ModelDecl> models,
                       List<Stmt> mainBody) {
            this.imports = imports;
            this.functions = functions;
            this.enums = enums;
            this.interfaces = interfaces;
            this.classes = classes;
            this.models = models;
            this.mainBody = mainBody;
        }
    }

    /** A persisted record type: model Name: with typed fields. */
    public static class ModelDecl {
        public final String name;
        public final List<FieldDecl> fields;
        public ModelDecl(String name, List<FieldDecl> fields) {
            this.name = name;
            this.fields = fields;
        }
    }

    public static class FnDecl {
        public final String name;
        public final List<String> params;
        public final List<Stmt> body;
        public FnDecl(String name, List<String> params, List<Stmt> body) {
            this.name = name;
            this.params = params;
            this.body = body;
        }
    }

    // ---- OOP declarations ----

    public static class EnumDecl {
        public final String name;
        public final List<String> variants;
        public EnumDecl(String name, List<String> variants) {
            this.name = name;
            this.variants = variants;
        }
    }

    /** A method signature with no body (used by interfaces). */
    public static class MethodSig {
        public final String name;
        public final List<String> params;
        public MethodSig(String name, List<String> params) {
            this.name = name;
            this.params = params;
        }
    }

    public static class InterfaceDecl {
        public final String name;
        public final List<MethodSig> methods;
        public InterfaceDecl(String name, List<MethodSig> methods) {
            this.name = name;
            this.methods = methods;
        }
    }

    public static class FieldDecl {
        public final String name;
        public final boolean isPrivate;
        public final String typeName;   // optional, may be null
        public final Expr defaultValue; // optional, may be null
        public FieldDecl(String name, boolean isPrivate, String typeName, Expr defaultValue) {
            this.name = name;
            this.isPrivate = isPrivate;
            this.typeName = typeName;
            this.defaultValue = defaultValue;
        }
    }

    public static class MethodDecl {
        public final String name;
        public final boolean isPrivate;
        public final List<String> params;
        public final List<Stmt> body;
        public MethodDecl(String name, boolean isPrivate, List<String> params, List<Stmt> body) {
            this.name = name;
            this.isPrivate = isPrivate;
            this.params = params;
            this.body = body;
        }
    }

    public static class ClassDecl {
        public final String name;
        public final List<String> supers; // parent class and/or interfaces
        public final List<FieldDecl> fields;
        public final List<MethodDecl> methods;
        public ClassDecl(String name, List<String> supers,
                         List<FieldDecl> fields, List<MethodDecl> methods) {
            this.name = name;
            this.supers = supers;
            this.fields = fields;
            this.methods = methods;
        }
    }

    // ---- Statements ----

    public interface Stmt {}

    public static class SetStmt implements Stmt {
        public final String name;
        public final String typeName; // optional, may be null
        public final Expr value;
        public SetStmt(String name, String typeName, Expr value) {
            this.name = name;
            this.typeName = typeName;
            this.value = value;
        }
    }

    public static class SetIndexStmt implements Stmt {
        public final String name;
        public final Expr index;
        public final Expr value;
        public SetIndexStmt(String name, Expr index, Expr value) {
            this.name = name;
            this.index = index;
            this.value = value;
        }
    }

    /** set target.field: value */
    public static class SetFieldStmt implements Stmt {
        public final Expr target;
        public final String field;
        public final Expr value;
        public SetFieldStmt(Expr target, String field, Expr value) {
            this.target = target;
            this.field = field;
            this.value = value;
        }
    }

    public static class PrintStmt implements Stmt {
        public final Expr value;
        public PrintStmt(Expr value) { this.value = value; }
    }

    public static class IfStmt implements Stmt {
        public final Expr condition;
        public final List<Stmt> thenBody;
        public final List<Stmt> elseBody;
        public IfStmt(Expr condition, List<Stmt> thenBody, List<Stmt> elseBody) {
            this.condition = condition;
            this.thenBody = thenBody;
            this.elseBody = elseBody;
        }
    }

    public static class WhileStmt implements Stmt {
        public final Expr condition;
        public final List<Stmt> body;
        public WhileStmt(Expr condition, List<Stmt> body) {
            this.condition = condition;
            this.body = body;
        }
    }

    public static class CaseClause {
        public final List<Expr> values; // one or more matched values
        public final List<Stmt> body;
        public CaseClause(List<Expr> values, List<Stmt> body) {
            this.values = values;
            this.body = body;
        }
    }

    public static class SwitchStmt implements Stmt {
        public final Expr subject;
        public final List<CaseClause> cases;
        public final List<Stmt> defaultBody; // may be null
        public SwitchStmt(Expr subject, List<CaseClause> cases, List<Stmt> defaultBody) {
            this.subject = subject;
            this.cases = cases;
            this.defaultBody = defaultBody;
        }
    }

    public static class ReturnStmt implements Stmt {
        public final Expr value;
        public ReturnStmt(Expr value) { this.value = value; }
    }

    /** save Model: recordExpr */
    public static class SaveStmt implements Stmt {
        public final String model;
        public final Expr record;
        public SaveStmt(String model, Expr record) {
            this.model = model;
            this.record = record;
        }
    }

    /** route "METHOD /path": <inline handler body> */
    public static class RouteStmt implements Stmt {
        public final String spec;
        public final List<Stmt> body;
        public RouteStmt(String spec, List<Stmt> body) {
            this.spec = spec;
            this.body = body;
        }
    }

    /** respond: value  (sends an HTTP response from inside a route handler) */
    public static class RespondStmt implements Stmt {
        public final Expr value;
        public RespondStmt(Expr value) { this.value = value; }
    }

    /** serve: port  (starts the web server and blocks) */
    public static class ServeStmt implements Stmt {
        public final Expr port;
        public ServeStmt(Expr port) { this.port = port; }
    }

    /** delete Model [where cond] */
    public static class DeleteStmt implements Stmt {
        public final String model;
        public final Expr where; // may be null (delete all)
        public DeleteStmt(String model, Expr where) {
            this.model = model;
            this.where = where;
        }
    }

    public static class CallStmt implements Stmt {
        public final Expr call; // CallExpr or MethodCall
        public CallStmt(Expr call) { this.call = call; }
    }

    // ---- Expressions ----

    public interface Expr {}

    public static class IntLit implements Expr {
        public final int value;
        public IntLit(int value) { this.value = value; }
    }

    public static class StringLit implements Expr {
        public final String value;
        public StringLit(String value) { this.value = value; }
    }

    public static class BoolLit implements Expr {
        public final boolean value;
        public BoolLit(boolean value) { this.value = value; }
    }

    public static class ArrayLit implements Expr {
        public final List<Expr> elements;
        public ArrayLit(List<Expr> elements) { this.elements = elements; }
    }

    /** { key: expr, key: expr } record/map literal (insertion-ordered). */
    public static class MapLit implements Expr {
        public final List<String> keys;
        public final List<Expr> values;
        public MapLit(List<String> keys, List<Expr> values) {
            this.keys = keys;
            this.values = values;
        }
    }

    /** find Model [where cond] → array of records */
    public static class FindExpr implements Expr {
        public final String model;
        public final Expr where; // may be null (all rows)
        public FindExpr(String model, Expr where) {
            this.model = model;
            this.where = where;
        }
    }

    public static class Var implements Expr {
        public final String name;
        public Var(String name) { this.name = name; }
    }

    public static class BinOp implements Expr {
        public final String op;
        public final Expr left, right;
        public BinOp(String op, Expr left, Expr right) {
            this.op = op; this.left = left; this.right = right;
        }
    }

    public static class Compare implements Expr {
        public final String op;
        public final Expr left, right;
        public Compare(String op, Expr left, Expr right) {
            this.op = op; this.left = left; this.right = right;
        }
    }

    public static class CallExpr implements Expr {
        public final String name;
        public final List<Expr> args;
        public CallExpr(String name, List<Expr> args) {
            this.name = name; this.args = args;
        }
    }

    public static class IndexExpr implements Expr {
        public final Expr target;
        public final Expr index;
        public IndexExpr(Expr target, Expr index) {
            this.target = target;
            this.index = index;
        }
    }

    /** target.field  (also used for EnumName.Variant) */
    public static class FieldAccess implements Expr {
        public final Expr target;
        public final String field;
        public FieldAccess(Expr target, String field) {
            this.target = target;
            this.field = field;
        }
    }

    /** target.method(args) */
    public static class MethodCall implements Expr {
        public final Expr target;
        public final String method;
        public final List<Expr> args;
        public MethodCall(Expr target, String method, List<Expr> args) {
            this.target = target;
            this.method = method;
            this.args = args;
        }
    }

    /** new ClassName(args) */
    public static class NewExpr implements Expr {
        public final String className;
        public final List<Expr> args;
        public NewExpr(String className, List<Expr> args) {
            this.className = className;
            this.args = args;
        }
    }
}
