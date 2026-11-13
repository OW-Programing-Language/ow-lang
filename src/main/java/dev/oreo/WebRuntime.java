package dev.oreo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
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
    private static final int MAX_BODY_BYTES = 1024 * 1024;

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
        List<String> segments = splitPath(path);
        for (Route route : routes) {
            if (route.method.equals(method) && route.segments.equals(segments))
                throw new IllegalArgumentException("Duplicate OW HTTP route: " + spec);
        }
        routes.add(new Route(method, segments, body));
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
        String url = "http://localhost:" + port + "/";
        out.println("Ow web server listening on " + url + "  (" + routes.size() + " routes)");
        openBrowser(url);
        // Block forever; the process is the server's lifetime.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void openBrowser(String url) {
        // Set OW_NO_BROWSER (or OW_UI_NO_BROWSER) to suppress, e.g. on a server.
        if (System.getenv("OW_NO_BROWSER") != null || System.getenv("OW_UI_NO_BROWSER") != null) return;
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ignored) { /* headless / server: just leave the URL printed */ }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            // Middleware always executes before any user route handler.
            for (RuntimeExtension extension : interp.runtimeExtensions()) {
                RuntimeExtension.Response blocked = extension.beforeRequest(ex);
                if (blocked != null) {
                    status = blocked.status();
                    send(ex, status, blocked.contentType(), blocked.body());
                    return;
                }
            }
            String method = ex.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
            List<String> pathSegs = splitPath(ex.getRequestURI().getPath());
            Route match = null;
            Interpreter.OwRecord params = new Interpreter.OwRecord();
            for (Route route : routes) {
                Interpreter.OwRecord p = new Interpreter.OwRecord();
                if (route.method.equals(method) && matches(route.segments, pathSegs, p)) {
                    match = route;
                    params = p;
                    break;
                }
            }
            if (match == null) {
                status = 404;
                send(ex, status, "text/plain; charset=utf-8", "Not found");
                return;
            }
            String declared = ex.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                try {
                    if (Long.parseLong(declared) > MAX_BODY_BYTES) {
                        status = 413;
                        send(ex, status, "text/plain; charset=utf-8", "Request body too large");
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    status = 400;
                    send(ex, status, "text/plain; charset=utf-8", "Invalid content length");
                    return;
                }
            }
            byte[] rawBody;
            try (InputStream bodyStream = ex.getRequestBody()) {
                rawBody = bodyStream.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (rawBody.length > MAX_BODY_BYTES) {
                status = 413;
                send(ex, status, "text/plain; charset=utf-8", "Request body too large");
                return;
            }
            Interpreter.OwRecord req = new Interpreter.OwRecord();
            req.put("method", method);
            req.put("path", ex.getRequestURI().getPath());
            req.put("query", parseQuery(ex.getRequestURI().getRawQuery()));
            req.put("params", params);
            req.put("body", new String(rawBody, StandardCharsets.UTF_8));
            Object result = interp.runRouteHandler(match.body, req);
            status = writeResponse(ex, result);
        } catch (Exception err) {
            out.println("OW HTTP request failed (" + err.getClass().getSimpleName() + ")");
            status = 500;
            try {
                send(ex, status, "text/plain; charset=utf-8", "Internal server error");
            } catch (IOException ignored) { ex.close(); }
        } finally {
            long elapsed = System.nanoTime() - started;
            for (RuntimeExtension extension : interp.runtimeExtensions()) {
                try { extension.afterRequest(ex, status, elapsed); }
                catch (RuntimeException ignored) {
                    out.println("OW HTTP extension afterRequest failed");
                }
            }
        }
    }

    private int writeResponse(HttpExchange ex, Object result) throws IOException {
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
        } else if (body instanceof String text) {
            String ct = text.stripLeading().startsWith("<") ?
                    "text/html; charset=utf-8" : "text/plain; charset=utf-8";
            send(ex, status, ct, text);
        } else {
            send(ex, status, "text/plain; charset=utf-8", interp.formatValue(body));
        }
        return status;
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
