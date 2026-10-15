package dev.oreo;

import java.util.*;
import static dev.oreo.Lexer.Type;

public class Parser {

    private final List<Lexer.Token> t;
    private int p = 0;

    public Parser(List<Lexer.Token> tokens) { this.t = tokens; }

    public Ast.Program parseProgram() {
        List<Ast.FnDecl> fns = new ArrayList<>();
        List<Ast.EnumDecl> enums = new ArrayList<>();
        List<Ast.InterfaceDecl> interfaces = new ArrayList<>();
        List<Ast.ClassDecl> classes = new ArrayList<>();
        List<Ast.Stmt> mainBody = null;

        consumeNewlines();
        while (!check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }

            if (matchIdent("fn")) {
                fns.add(parseFnDecl());
            } else if (matchIdent("enum")) {
                enums.add(parseEnum());
            } else if (matchIdent("interface")) {
                interfaces.add(parseInterface());
            } else if (matchIdent("class")) {
                classes.add(parseClass());
            } else if (matchIdent("main")) {
                if (mainBody != null) throw err("Duplicate 'main' block");
                mainBody = parseBlockHeaderless();
            } else {
                throw err("Expected 'fn', 'enum', 'interface', 'class' or 'main' at top level, got "
                        + peek().type + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
            }
            consumeNewlines();
        }

        if (mainBody == null) throw err("Program is missing a 'main:' block");
        return new Ast.Program(fns, enums, interfaces, classes, mainBody);
    }

    private Ast.FnDecl parseFnDecl() {
        String name = expect(Type.IDENT).text;
        List<String> params = parseParamList();
        List<Ast.Stmt> body = parseBlockHeaderless();
        return new Ast.FnDecl(name, params, body);
    }

    private List<String> parseParamList() {
        expect(Type.LPAREN);
        List<String> params = new ArrayList<>();
        if (!check(Type.RPAREN)) {
            params.add(expect(Type.IDENT).text);
            while (match(Type.COMMA)) params.add(expect(Type.IDENT).text);
        }
        expect(Type.RPAREN);
        return params;
    }

    // enum Name:
    //   Variant
    //   Variant
    private Ast.EnumDecl parseEnum() {
        String name = expect(Type.IDENT).text;
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<String> variants = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            variants.add(expect(Type.IDENT).text);
            expect(Type.NEWLINE);
        }
        expect(Type.DEDENT);
        if (variants.isEmpty()) throw err("enum '" + name + "' has no variants");
        return new Ast.EnumDecl(name, variants);
    }

    // interface Name:
    //   fn method(params)
    private Ast.InterfaceDecl parseInterface() {
        String name = expect(Type.IDENT).text;
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.MethodSig> methods = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            if (!matchIdent("fn")) throw err("interface body expects 'fn' signatures");
            String m = expect(Type.IDENT).text;
            List<String> params = parseParamList();
            expect(Type.NEWLINE);
            methods.add(new Ast.MethodSig(m, params));
        }
        expect(Type.DEDENT);
        return new Ast.InterfaceDecl(name, methods);
    }

    // class Name[(Super, Iface...)]:
    //   field [private|public] name [type] [: default]
    //   fn [private|public] name(params): body
    private Ast.ClassDecl parseClass() {
        String name = expect(Type.IDENT).text;
        List<String> supers = new ArrayList<>();
        if (match(Type.LPAREN)) {
            if (!check(Type.RPAREN)) {
                supers.add(expect(Type.IDENT).text);
                while (match(Type.COMMA)) supers.add(expect(Type.IDENT).text);
            }
            expect(Type.RPAREN);
        }
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);

        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            if (matchIdent("field")) {
                fields.add(parseField());
            } else if (matchIdent("fn")) {
                methods.add(parseMethod());
            } else {
                throw err("class body expects 'field' or 'fn', got "
                        + peek().type + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
            }
        }
        expect(Type.DEDENT);
        return new Ast.ClassDecl(name, supers, fields, methods);
    }

    private Ast.FieldDecl parseField() {
        boolean isPrivate = parseVisibility();
        String name = expect(Type.IDENT).text;
        String typeName = null;
        if (check(Type.IDENT)) {
            typeName = expect(Type.IDENT).text;
        }
        Ast.Expr def = null;
        if (match(Type.COLON)) {
            def = parseExpr();
        }
        expect(Type.NEWLINE);
        return new Ast.FieldDecl(name, isPrivate, typeName, def);
    }

    private Ast.MethodDecl parseMethod() {
        boolean isPrivate = parseVisibility();
        String name = expect(Type.IDENT).text;
        List<String> params = parseParamList();
        List<Ast.Stmt> body = parseBlockHeaderless();
        return new Ast.MethodDecl(name, isPrivate, params, body);
    }

    private boolean parseVisibility() {
        if (matchIdent("private")) return true;
        if (matchIdent("public")) return false;
        return false; // default public
    }

    /** Parses ": NEWLINE INDENT stmts DEDENT" — header keyword already consumed. */
    private List<Ast.Stmt> parseBlockHeaderless() {
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> body = parseStmtsUntilDedent();
        expect(Type.DEDENT);
        return body;
    }

    private List<Ast.Stmt> parseStmtsUntilDedent() {
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            body.add(parseStmt());
        }
        return body;
    }

    private Ast.Stmt parseStmt() {
        if (matchIdent("set")) {
            return parseSet();
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
            if (!(expr instanceof Ast.CallExpr) && !(expr instanceof Ast.MethodCall)) {
                throw err("'call:' expects a function or method call expression");
            }
            return new Ast.CallStmt(expr);
        }
        if (matchIdent("if")) {
            return parseIf();
        }
        if (matchIdent("while")) {
            return parseWhile();
        }
        if (matchIdent("switch")) {
            return parseSwitch();
        }
        throw err("Expected statement (set/print/if/while/switch/return/call), got "
                + peek().type + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
    }

    private Ast.Stmt parseSet() {
        String name = expect(Type.IDENT).text;

        // Field assignment: set a.b.c: value
        if (check(Type.DOT)) {
            Ast.Expr target = new Ast.Var(name);
            expect(Type.DOT);
            String field = expect(Type.IDENT).text;
            while (check(Type.DOT)) {
                expect(Type.DOT);
                target = new Ast.FieldAccess(target, field);
                field = expect(Type.IDENT).text;
            }
            expect(Type.COLON);
            Ast.Expr value = parseExpr();
            expect(Type.NEWLINE);
            return new Ast.SetFieldStmt(target, field, value);
        }

        // Indexed assignment: set name[idx]: value
        if (match(Type.LBRACKET)) {
            Ast.Expr index = parseExpr();
            expect(Type.RBRACKET);
            expect(Type.COLON);
            Ast.Expr value = parseExpr();
            expect(Type.NEWLINE);
            return new Ast.SetIndexStmt(name, index, value);
        }

        // Plain or typed assignment: set name [type]: value
        String typeName = null;
        if (check(Type.IDENT)) {
            typeName = expect(Type.IDENT).text;
        }
        expect(Type.COLON);
        Ast.Expr value = parseExpr();
        expect(Type.NEWLINE);
        return new Ast.SetStmt(name, typeName, value);
    }

    private Ast.IfStmt parseIf() {
        expect(Type.COLON);
        Ast.Expr cond = parseExpr();
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> thenBody = parseStmtsUntilDedent();
        expect(Type.DEDENT);

        List<Ast.Stmt> elseBody = null;
        if (matchIdent("else")) {
            // else if ...
            if (matchIdent("if")) {
                elseBody = new ArrayList<>();
                elseBody.add(parseIf());
            } else {
                expect(Type.COLON);
                expect(Type.NEWLINE);
                expect(Type.INDENT);
                elseBody = parseStmtsUntilDedent();
                expect(Type.DEDENT);
            }
        }
        return new Ast.IfStmt(cond, thenBody, elseBody);
    }

    private Ast.WhileStmt parseWhile() {
        expect(Type.COLON);
        Ast.Expr cond = parseExpr();
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> body = parseStmtsUntilDedent();
        expect(Type.DEDENT);
        return new Ast.WhileStmt(cond, body);
    }

    // switch: subject
    //   case v[, v2]:
    //     body
    //   default:
    //     body
    private Ast.SwitchStmt parseSwitch() {
        expect(Type.COLON);
        Ast.Expr subject = parseExpr();
        expect(Type.NEWLINE);
        expect(Type.INDENT);

        List<Ast.CaseClause> cases = new ArrayList<>();
        List<Ast.Stmt> defaultBody = null;
        while (!check(Type.DEDENT) && !check(Type.EOF)) {
            if (check(Type.NEWLINE)) { p++; continue; }
            if (matchIdent("case")) {
                List<Ast.Expr> values = new ArrayList<>();
                values.add(parseExpr());
                while (match(Type.COMMA)) values.add(parseExpr());
                List<Ast.Stmt> body = parseBlockAfterColon();
                cases.add(new Ast.CaseClause(values, body));
            } else if (matchIdent("default")) {
                if (defaultBody != null) throw err("switch has more than one 'default'");
                defaultBody = parseBlockAfterColon();
            } else {
                throw err("switch body expects 'case' or 'default', got "
                        + peek().type + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
            }
        }
        expect(Type.DEDENT);
        return new Ast.SwitchStmt(subject, cases, defaultBody);
    }

    /** Parses ": NEWLINE INDENT stmts DEDENT" where the ':' has not yet been consumed. */
    private List<Ast.Stmt> parseBlockAfterColon() {
        expect(Type.COLON);
        expect(Type.NEWLINE);
        expect(Type.INDENT);
        List<Ast.Stmt> body = parseStmtsUntilDedent();
        expect(Type.DEDENT);
        return body;
    }

    // expr        := comparison
    // comparison  := add ((== | != | < | > | <= | >=) add)*
    // add         := mul (( + | - ) mul)*
    // mul         := postfix (( * | / ) postfix)*
    // postfix     := primary ( "[" expr "]" | "." ident ["(" args ")"] )*
    // primary     := INT | STRING | array | "(" expr ")" | "new" ident "(" args ")"
    //               | IDENT [ "(" args ")" ]

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
        while (true) {
            if (match(Type.LBRACKET)) {
                Ast.Expr index = parseExpr();
                expect(Type.RBRACKET);
                expr = new Ast.IndexExpr(expr, index);
            } else if (match(Type.DOT)) {
                String member = expect(Type.IDENT).text;
                if (match(Type.LPAREN)) {
                    List<Ast.Expr> args = parseArgs();
                    expr = new Ast.MethodCall(expr, member, args);
                } else {
                    expr = new Ast.FieldAccess(expr, member);
                }
            } else {
                break;
            }
        }
        return expr;
    }

    private List<Ast.Expr> parseArgs() {
        List<Ast.Expr> args = new ArrayList<>();
        if (!check(Type.RPAREN)) {
            args.add(parseExpr());
            while (match(Type.COMMA)) args.add(parseExpr());
        }
        expect(Type.RPAREN);
        return args;
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
        if (matchIdent("new")) {
            String className = expect(Type.IDENT).text;
            expect(Type.LPAREN);
            List<Ast.Expr> args = parseArgs();
            return new Ast.NewExpr(className, args);
        }
        if (check(Type.IDENT)) {
            String name = expect(Type.IDENT).text;
            if (match(Type.LPAREN)) {
                List<Ast.Expr> args = parseArgs();
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
        if (!check(type)) throw err("Expected " + type + ", got " + peek().type
                + (peek().type == Type.IDENT ? "(" + peek().text + ")" : ""));
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
