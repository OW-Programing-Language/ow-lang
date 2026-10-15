package dev.oreo;

import java.util.*;

public class Lexer {

    public enum Type {
        IDENT, INT, STRING,
        COLON, COMMA, DOT, NEWLINE,
        INDENT, DEDENT,
        PLUS, MINUS, STAR, SLASH,
        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
        EQ_EQ, NEQ, LT, GT, LE, GE,
        EOF
    }

    public static class Token {
        public final Type type;
        public final String text;
        public final int line;

        public Token(Type type, String text, int line) {
            this.type = type;
            this.text = text;
            this.line = line;
        }

        @Override public String toString() {
            return type + "(" + text + ")@" + line;
        }
    }

    private final String[] lines;

    public Lexer(String src) {
        String normalized = src.replace("\r\n", "\n").replace('\r', '\n');
        this.lines = normalized.split("\n", -1);
    }

    public List<Token> lexAll() {
        List<Token> out = new ArrayList<>();
        Deque<Integer> indents = new ArrayDeque<>();
        indents.push(0);

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];

            String stripped = stripComment(line);
            if (stripped.trim().isEmpty()) continue;

            int leadingSpaces = countLeadingSpaces(line);
            if (leadingSpaces % 2 != 0) {
                throw err("Indentation must be a multiple of 2 spaces", lineNo);
            }

            int currentIndent = indents.peek();
            if (leadingSpaces > currentIndent) {
                indents.push(leadingSpaces);
                out.add(new Token(Type.INDENT, "", lineNo));
            } else if (leadingSpaces < currentIndent) {
                while (indents.size() > 1 && leadingSpaces < indents.peek()) {
                    indents.pop();
                    out.add(new Token(Type.DEDENT, "", lineNo));
                }
                if (leadingSpaces != indents.peek()) {
                    throw err("Invalid dedent level", lineNo);
                }
            }

            lexLine(stripped.substring(leadingSpaces), lineNo, out);
            out.add(new Token(Type.NEWLINE, "", lineNo));
        }

        while (indents.size() > 1) {
            indents.pop();
            out.add(new Token(Type.DEDENT, "", lines.length));
        }

        out.add(new Token(Type.EOF, "", lines.length));
        return out;
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inString = !inString;
            else if (c == '#' && !inString) return line.substring(0, i);
        }
        return line;
    }

    private void lexLine(String line, int lineNo, List<Token> out) {
        int p = 0;
        while (p < line.length()) {
            char c = line.charAt(p);

            if (c == ' ' || c == '\t') { p++; continue; }

            switch (c) {
                case ':': out.add(new Token(Type.COLON,  ":", lineNo)); p++; continue;
                case ',': out.add(new Token(Type.COMMA,  ",", lineNo)); p++; continue;
                case '.': out.add(new Token(Type.DOT,    ".", lineNo)); p++; continue;
                case '+': out.add(new Token(Type.PLUS,   "+", lineNo)); p++; continue;
                case '-': out.add(new Token(Type.MINUS,  "-", lineNo)); p++; continue;
                case '*': out.add(new Token(Type.STAR,   "*", lineNo)); p++; continue;
                case '/': out.add(new Token(Type.SLASH,  "/", lineNo)); p++; continue;
                case '(': out.add(new Token(Type.LPAREN, "(", lineNo)); p++; continue;
                case ')': out.add(new Token(Type.RPAREN, ")", lineNo)); p++; continue;
                case '[': out.add(new Token(Type.LBRACKET, "[", lineNo)); p++; continue;
                case ']': out.add(new Token(Type.RBRACKET, "]", lineNo)); p++; continue;
                case '{': out.add(new Token(Type.LBRACE,   "{", lineNo)); p++; continue;
                case '}': out.add(new Token(Type.RBRACE,   "}", lineNo)); p++; continue;
            }

            if (c == '=') {
                if (p + 1 < line.length() && line.charAt(p + 1) == '=') {
                    out.add(new Token(Type.EQ_EQ, "==", lineNo)); p += 2; continue;
                }
                throw err("Use '==' for equality", lineNo);
            }
            if (c == '!') {
                if (p + 1 < line.length() && line.charAt(p + 1) == '=') {
                    out.add(new Token(Type.NEQ, "!=", lineNo)); p += 2; continue;
                }
                throw err("Unexpected '!'", lineNo);
            }
            if (c == '<') {
                if (p + 1 < line.length() && line.charAt(p + 1) == '=') {
                    out.add(new Token(Type.LE, "<=", lineNo)); p += 2; continue;
                }
                out.add(new Token(Type.LT, "<", lineNo)); p++; continue;
            }
            if (c == '>') {
                if (p + 1 < line.length() && line.charAt(p + 1) == '=') {
                    out.add(new Token(Type.GE, ">=", lineNo)); p += 2; continue;
                }
                out.add(new Token(Type.GT, ">", lineNo)); p++; continue;
            }

            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                p++;
                while (p < line.length() && line.charAt(p) != '"') {
                    char ch = line.charAt(p);
                    if (ch == '\\' && p + 1 < line.length()) {
                        char esc = line.charAt(p + 1);
                        switch (esc) {
                            case 'n':  sb.append('\n'); break;
                            case 't':  sb.append('\t'); break;
                            case '"':  sb.append('"');  break;
                            case '\\': sb.append('\\'); break;
                            default: sb.append(esc);
                        }
                        p += 2;
                    } else {
                        sb.append(ch);
                        p++;
                    }
                }
                if (p >= line.length()) throw err("Unterminated string literal", lineNo);
                p++;
                out.add(new Token(Type.STRING, sb.toString(), lineNo));
                continue;
            }

            if (isDigit(c)) {
                int start = p;
                while (p < line.length() && isDigit(line.charAt(p))) p++;
                out.add(new Token(Type.INT, line.substring(start, p), lineNo));
                continue;
            }

            if (isAlpha(c) || c == '_') {
                int start = p;
                while (p < line.length()) {
                    char ch = line.charAt(p);
                    if (!(isAlpha(ch) || isDigit(ch) || ch == '_')) break;
                    p++;
                }
                out.add(new Token(Type.IDENT, line.substring(start, p), lineNo));
                continue;
            }

            throw err("Unexpected character: " + c, lineNo);
        }
    }

    private static int countLeadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isAlpha(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

    private static RuntimeException err(String msg, int line) {
        return new RuntimeException("Lexer error at line " + line + ": " + msg);
    }
}
