package dev.oreo;

import java.util.*;

public class Ast {

    public static class Program {
        public final List<FnDecl> functions;
        public final List<Stmt> mainBody;
        public Program(List<FnDecl> functions, List<Stmt> mainBody) {
            this.functions = functions;
            this.mainBody = mainBody;
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

    public interface Stmt {}

    public static class SetStmt implements Stmt {
        public final String name;
        public final Expr value;
        public SetStmt(String name, Expr value) { this.name = name; this.value = value; }
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

    public static class ReturnStmt implements Stmt {
        public final Expr value;
        public ReturnStmt(Expr value) { this.value = value; }
    }

    public static class CallStmt implements Stmt {
        public final CallExpr call;
        public CallStmt(CallExpr call) { this.call = call; }
    }

    public interface Expr {}

    public static class IntLit implements Expr {
        public final int value;
        public IntLit(int value) { this.value = value; }
    }

    public static class StringLit implements Expr {
        public final String value;
        public StringLit(String value) { this.value = value; }
    }

    public static class ArrayLit implements Expr {
        public final List<Expr> elements;
        public ArrayLit(List<Expr> elements) { this.elements = elements; }
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
}
