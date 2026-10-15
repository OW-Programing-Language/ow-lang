package dev.oreo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for Ow values (used to persist the built-in
 * database). Supported value types: Integer, String, Boolean, null, List,
 * and {@link Interpreter.OwRecord} (objects). Ow has no floats, so numbers are
 * read as integers.
 */
public final class Json {
    private Json() {}

    // ---- writer ----

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Integer || v instanceof Long) { sb.append(v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(list.get(i), sb);
            }
            sb.append(']');
            return;
        }
        writeString(String.valueOf(v), sb);
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    // ---- reader ----

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        Interpreter.OwRecord object() {
            Interpreter.OwRecord m = new Interpreter.OwRecord();
            expect('{'); ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String key = string();
                ws(); expect(':');
                Object v = value();
                m.put(key, v);
                ws();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw err("expected , or } in object");
            }
            return m;
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            expect('['); ws();
            if (peek() == ']') { i++; return list; }
            while (true) {
                list.add(value());
                ws();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw err("expected , or ] in array");
            }
            return list;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        Object number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            String num = s.substring(start, i);
            try { return Integer.parseInt(num); }
            catch (NumberFormatException ex) { return (int) Double.parseDouble(num); }
        }

        Object bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("invalid literal");
        }

        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("invalid literal");
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        char next() { return i < s.length() ? s.charAt(i++) : '\0'; }
        void expect(char c) { if (next() != c) throw err("expected '" + c + "'"); }
        RuntimeException err(String msg) { return new RuntimeException("JSON parse error at " + i + ": " + msg); }
    }
}
