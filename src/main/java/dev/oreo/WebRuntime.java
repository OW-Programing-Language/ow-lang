package dev.oreo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A tiny, dependency-free HTTP server that lets an Ow program act as a web
 * backend. Routes are registered with inline handler bodies ({@code route}) and
 * the server is started with {@code serve}. Handlers read the request via the
 * {@code req} record / request builtins and reply with {@code respond:}.
 *
 * Requests are dispatched on a single thread, so handlers (and the built-in
 * database they often touch) run serially without data races.
 */
public class WebRuntime {

    private final Interpreter interp;
    private final PrintStream out;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;

    public WebRuntime(Interpreter interp, PrintStream out) {
        this.interp = interp;
        this.out = out;
    }

    private static final class Route {
        final String method;
        final List<String> segments; // literal, or ":name" for a path param
        final List<Ast.Stmt> body;
        Route(String method, List<String> segments, List<Ast.Stmt> body) {
            this.method = method;
            this.segments = segments;
            this.body = body;
        }
    }

    /** spec is like "GET /users/:id" (method optional, defaults to GET). */
    public void register(String spec, List<Ast.Stmt> body) {
        String method = "GET";
        String path = spec.trim();
        int sp = path.indexOf(' ');
        if (sp > 0) {
            method = path.substring(0, sp).trim().toUpperCase();
            path = path.substring(sp + 1).trim();
        }
        routes.add(new Route(method, splitPath(path), body));
    }

    public void serve(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Could not start web server on port " + port + ": " + e.getMessage(), e);
        }
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        out.println("Ow web server listening on http://localhost:" + port + "  (" + routes.size() + " routes)");
        // Block forever; the process is the server's lifetime.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod().toUpperCase();
            List<String> pathSegs = splitPath(ex.getRequestURI().getPath());

            Route match = null;
            Interpreter.OwRecord params = new Interpreter.OwRecord();
            for (Route r : routes) {
                Interpreter.OwRecord p = new Interpreter.OwRecord();
                if (r.method.equals(method) && matches(r.segments, pathSegs, p)) {
                    match = r;
                    params = p;
                    break;
                }
            }

            if (match == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Interpreter.OwRecord req = new Interpreter.OwRecord();
            req.put("method", method);
            req.put("path", ex.getRequestURI().getPath());
            req.put("query", parseQuery(ex.getRequestURI().getRawQuery()));
            req.put("params", params);
            req.put("body", body);

            Object result = interp.runRouteHandler(match.body, req);
            writeResponse(ex, result);
        } catch (RuntimeException err) {
            out.println("Ow route error: " + err.getMessage());
            send(ex, 500, "text/plain; charset=utf-8", "Internal server error: " + err.getMessage());
        }
    }

    private void writeResponse(HttpExchange ex, Object result) throws IOException {
        int status = 200;
        Object body = result;
        if (result instanceof Interpreter.OwResponse resp) {
            status = resp.status;
            body = resp.body;
        }
        if (body == null) {
            send(ex, status, "text/plain; charset=utf-8", "");
        } else if (body instanceof Interpreter.OwRecord || body instanceof List<?>) {
            send(ex, status, "application/json; charset=utf-8", Json.write(body));
        } else if (body instanceof String s) {
            String ct = s.stripLeading().startsWith("<") ? "text/html; charset=utf-8" : "text/plain; charset=utf-8";
            send(ex, status, ct, s);
        } else {
            send(ex, status, "text/plain; charset=utf-8", interp.formatValue(body));
        }
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } else {
            ex.close();
        }
    }

    private static boolean matches(List<String> pattern, List<String> path, Interpreter.OwRecord params) {
        if (pattern.size() != path.size()) return false;
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            if (p.startsWith(":")) {
                params.put(p.substring(1), path.get(i));
            } else if (!p.equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitPath(String path) {
        List<String> segs = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) segs.add(s);
        }
        return segs;
    }

    private static Interpreter.OwRecord parseQuery(String raw) {
        Interpreter.OwRecord q = new Interpreter.OwRecord();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                q.put(URLDecoder.decode(part, StandardCharsets.UTF_8), "");
            } else {
                q.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                      URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }
}
