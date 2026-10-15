package dev.oreo;

import java.util.*;
import static dev.oreo.Lexer.Type;

public class Parser {

    private final List<Lexer.Token> t;
    private int p = 0;

    public Parser(List<Lexer.Token> tokens) { this.t = tokens; }

    public Ast.Program parseProgram() {
        List<Ast.FnDecl> fns = new ArrayList<>();
        List<Ast.Stmt> mainBody = null;

        consumeNewlines();
        while (!check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }

            if (matchIdent("fn")) {
                fns.add(parseFnDecl());
            } else if (matchIdent("main")) {
                if (mainBody != null) throw err("Duplicate 'main' block");
                mainBody = parseBlockHeaderless();
            } else {
                throw err("Expected 'fn' or 'main' at top level, got " + peek().type
                        + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
            }
            consumeNewlines();
        }

        if (mainBody == null) throw err("Program is missing a 'main:' block");
        return new Ast.Program(fns, mainBody);
    }

    private Ast.FnDecl parseFnDecl() {
        String name = expect(Type.IDENT).text;
        expect(Type.LPAREN);
        List<String> params = new ArrayList<>();
        if (!check(Type.RPAREN)) {
            params.add(expect(Type.IDENT).text);
            while (match(Type.COMMA)) params.add(expect(Type.IDENT).text);
        }
        expect(Type.RPAREN);
        List<Ast.Stmt> body = parseBlockHeaderless();
        return new Ast.FnDecl(name, params, body);
    }

    /** Parses ": NEWLINE INDENT stmts DEDENT" — header keyword already consumed. */
    private List<Ast.Stmt> parseBlockHeaderless() {
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            body.add(parseStmt());
        }
        expect(Type.DEDENT);
        return body;
    }

    private Ast.Stmt parseStmt() {
        if (matchIdent("set")) {
            String name = expect(Type.IDENT).text;
            if (match(Type.LBRACKET)) {
                Ast.Expr index = parseExpr();
                expect(Type.RBRACKET);
                expect(Type.COLON);
                Ast.Expr value = parseExpr();
                expect(Type.NEWLINE);
                return new Ast.SetIndexStmt(name, index, value);
            }
            expect(Type.COLON);
            Ast.Expr expr = parseExpr();
            expect(Type.NEWLINE);
            return new Ast.SetStmt(name, expr);
        }
        if (matchIdent("print")) {
            expect(Type.COLON);
            Ast.Expr expr = parseExpr();
            expect(Type.NEWLINE);
            return new Ast.PrintStmt(expr);
        }
        if (matchIdent("return")) {
            expect(Type.COLON);
            Ast.Expr expr = parseExpr();
            expect(Type.NEWLINE);
            return new Ast.ReturnStmt(expr);
        }
        if (matchIdent("call")) {
            expect(Type.COLON);
            Ast.Expr expr = parseExpr();
            expect(Type.NEWLINE);
            if (!(expr instanceof Ast.CallExpr ce)) {
                throw err("'call:' expects a function call expression");
            }
            return new Ast.CallStmt(ce);
        }
        if (matchIdent("if")) {
            return parseIf();
        }
        if (matchIdent("while")) {
            return parseWhile();
        }
        throw err("Expected statement (set/print/if/while/return/call), got "
                + peek().type + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
    }

    private Ast.IfStmt parseIf() {
        expect(Type.COLON);
        Ast.Expr cond = parseExpr();
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> thenBody = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            thenBody.add(parseStmt());
        }
        expect(Type.DEDENT);

        List<Ast.Stmt> elseBody = null;
        if (matchIdent("else")) {
            expect(Type.COLON);
            expect(Type.NEWLINE);
            expect(Type.INDENT);
            elseBody = new ArrayList<>();
            while (!check(Type.DEDENT) && !check(Type.EOF)) {
                if (check(Type.NEWLINE)) { p++; continue; }
                elseBody.add(parseStmt());
            }
            expect(Type.DEDENT);
        }
        return new Ast.IfStmt(cond, thenBody, elseBody);
    }

    private Ast.WhileStmt parseWhile() {
        expect(Type.COLON);
        Ast.Expr cond = parseExpr();
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            body.add(parseStmt());
        }
        expect(Type.DEDENT);
        return new Ast.WhileStmt(cond, body);
    }

    // expr        := comparison
    // comparison  := add ((== | != | < | > | <= | >=) add)*
    // add         := mul (( + | - ) mul)*
    // mul         := postfix (( * | / ) postfix)*
    // postfix     := primary ( "[" expr "]" )*
    // primary     := INT | STRING | array | "(" expr ")" | IDENT [ "(" args ")" ]

    private Ast.Expr parseExpr() {
        Ast.Expr left = parseAdd();
        while (checkAny(Type.EQ_EQ, Type.NEQ, Type.LT, Type.GT, Type.LE, Type.GE)) {
            String op = peek().text;
            p++;
            Ast.Expr right = parseAdd();
            left = new Ast.Compare(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseAdd() {
        Ast.Expr left = parseMul();
        while (check(Type.PLUS) || check(Type.MINUS)) {
            String op = peek().text;
            p++;
            Ast.Expr right = parseMul();
            left = new Ast.BinOp(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseMul() {
        Ast.Expr left = parsePostfix();
        while (check(Type.STAR) || check(Type.SLASH)) {
            String op = peek().text;
            p++;
            Ast.Expr right = parsePostfix();
            left = new Ast.BinOp(op, left, right);
        }
        return left;
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (match(Type.LBRACKET)) {
            Ast.Expr index = parseExpr();
            expect(Type.RBRACKET);
            expr = new Ast.IndexExpr(expr, index);
        }
        return expr;
    }

    private Ast.Expr parsePrimary() {
        if (check(Type.INT)) {
            int v = Integer.parseInt(expect(Type.INT).text);
            return new Ast.IntLit(v);
        }
        if (check(Type.STRING)) {
            return new Ast.StringLit(expect(Type.STRING).text);
        }
        if (match(Type.LBRACKET)) {
            List<Ast.Expr> elements = new ArrayList<>();
            if (!check(Type.RBRACKET)) {
                elements.add(parseExpr());
                while (match(Type.COMMA)) elements.add(parseExpr());
            }
            expect(Type.RBRACKET);
            return new Ast.ArrayLit(elements);
        }
        if (match(Type.LPAREN)) {
            Ast.Expr inner = parseExpr();
            expect(Type.RPAREN);
            return inner;
        }
        if (check(Type.IDENT)) {
            String name = expect(Type.IDENT).text;
            if (match(Type.LPAREN)) {
                List<Ast.Expr> args = new ArrayList<>();
                if (!check(Type.RPAREN)) {
                    args.add(parseExpr());
                    while (match(Type.COMMA)) args.add(parseExpr());
                }
                expect(Type.RPAREN);
                return new Ast.CallExpr(name, args);
            }
            return new Ast.Var(name);
        }
        throw err("Expected expression");
    }

    // helpers
    private void consumeNewlines() {
        while (check(Type.NEWLINE)) p++;
    }

    private boolean matchIdent(String s) {
        if (check(Type.IDENT) && peek().text.equals(s)) { p++; return true; }
        return false;
    }

    private boolean match(Type type) {
        if (check(type)) { p++; return true; }
        return false;
    }

    private Lexer.Token expect(Type type) {
        if (!check(type)) throw err("Expected " + type + ", got " + peek().type);
        return t.get(p++);
    }

    private boolean check(Type type) { return peek().type == type; }

    private boolean checkAny(Type... types) {
        for (Type ty : types) if (peek().type == ty) return true;
        return false;
    }

    private Lexer.Token peek() { return t.get(p); }

    private RuntimeException err(String msg) {
        int line = peek().line;
        return new RuntimeException("Parser error at line " + line + ": " + msg);
    }
}
