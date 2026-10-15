package dev.oreo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders an Ow UI tree as a modern web page served from an embedded,
 * dependency-free HTTP server. Button clicks are routed back into the
 * interpreter by calling the named Ow handler function.
 *
 * The Ow program provides a render function (by name) that returns a widget
 * tree built with the ui_* builtins. After every event the render function is
 * called again and the browser re-syncs.
 */
public class UiRuntime {

    private final Interpreter interp;
    private final PrintStream out;

    private HttpServer server;
    private String renderFn;
    private String title = "Ow App";

    private final Object lock = new Object();
    private volatile int version = 0;
    private String cachedJson = "null";
    private final Map<String, Event> events = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger();

    private final CountDownLatch stopLatch = new CountDownLatch(1);

    private static final class Event {
        final String handler;
        final boolean hasArg;
        final Object arg;
        Event(String handler, boolean hasArg, Object arg) {
            this.handler = handler; this.hasArg = hasArg; this.arg = arg;
        }
    }

    public UiRuntime(Interpreter interp, PrintStream out) {
        this.interp = interp;
        this.out = out;
    }

    public void run(String renderFn, String title) {
        this.renderFn = renderFn;
        this.title = title;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException("Could not start Ow UI server: " + e.getMessage(), e);
        }
        server.createContext("/", this::handleRoot);
        server.createContext("/state", this::handleState);
        server.createContext("/event", this::handleEvent);
        server.setExecutor(null);
        server.start();

        rerender(); // initial render

        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/";
        out.println("Ow UI running at " + url);
        openBrowser(url);

        try {
            stopLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void requestStop() { stopLatch.countDown(); }

    public void shutdown() {
        stopLatch.countDown();
        if (server != null) server.stop(0);
    }

    private void rerender() {
        synchronized (lock) {
            events.clear();
            idSeq.set(0);
            Object tree = interp.callFunction(renderFn, Collections.emptyList());
            StringBuilder sb = new StringBuilder();
            writeJson(tree, sb);
            cachedJson = sb.toString();
            version++;
        }
    }

    // ---- HTTP handlers ----

    private void handleRoot(HttpExchange ex) throws IOException {
        byte[] body = PAGE.replace("{{TITLE}}", escapeHtml(title)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleState(HttpExchange ex) throws IOException {
        String json;
        int v;
        synchronized (lock) { json = cachedJson; v = version; }
        String payload = "{\"version\":" + v + ",\"tree\":" + json + "}";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleEvent(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String id = param(query, "id");
        Event event = (id != null) ? events.get(id) : null;
        if (event != null) {
            try {
                List<Object> args = event.hasArg
                        ? Collections.singletonList(event.arg)
                        : Collections.emptyList();
                interp.callFunction(event.handler, args);
            } catch (RuntimeException err) {
                out.println("Ow UI handler error: " + err.getMessage());
            }
            rerender();
        }
        int v;
        synchronized (lock) { v = version; }
        byte[] body = ("{\"version\":" + v + "}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String param(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void openBrowser(String url) {
        if (System.getenv("OW_UI_NO_BROWSER") != null) {
            out.println("Open this URL in your browser: " + url);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) { /* fall through */ }
        out.println("Open this URL in your browser: " + url);
    }

    // ---- JSON serialisation of the widget tree (assigns button event ids) ----

    @SuppressWarnings("unchecked")
    private void writeJson(Object node, StringBuilder sb) {
        if (node == null) { sb.append("null"); return; }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            sb.append('{');
            boolean first = true;
            // register button events and inject an id
            if ("button".equals(m.get("type"))) {
                String id = "e" + idSeq.incrementAndGet();
                boolean hasArg = m.containsKey("arg");
                events.put(id, new Event(String.valueOf(m.get("onClick")), hasArg, m.get("arg")));
                sb.append("\"id\":\"").append(id).append('"');
                first = false;
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey().equals("onClick") || e.getKey().equals("arg")) continue;
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeJson(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (node instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeJson(list.get(i), sb);
            }
            sb.append(']');
            return;
        }
        if (node instanceof Integer || node instanceof Boolean) {
            sb.append(node.toString());
            return;
        }
        writeString(interp.formatValue(node), sb);
    }

    private void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- The web page: modern HTML/CSS/JS that renders the Ow widget tree ----

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>{{TITLE}}</title>
            <style>
              :root{
                --bg:#0f1117; --panel:#171a23; --panel2:#1f2430;
                --text:#e7e9ee; --muted:#9aa3b2; --accent:#6366f1; --accent2:#22d3ee;
                --red:#ef4444; --yellow:#f6c343; --empty:#0b0e14; --radius:16px;
              }
              @media (prefers-color-scheme: light){
                :root{ --bg:#f4f5f8; --panel:#ffffff; --panel2:#eef0f5;
                       --text:#1b1e27; --muted:#5b6472; --empty:#e6e9f0; }
              }
              *{box-sizing:border-box}
              body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                   background:radial-gradient(1200px 800px at 70% -10%, #1e2230 0%, var(--bg) 60%);
                   color:var(--text);
                   font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,Inter,sans-serif;}
              #app{background:var(--panel);border-radius:24px;padding:28px 32px;
                   box-shadow:0 20px 60px rgba(0,0,0,.35);min-width:320px;}
              .ow-col{display:flex;flex-direction:column;gap:14px;align-items:center;}
              .ow-row{display:flex;flex-direction:row;gap:12px;align-items:center;justify-content:center;flex-wrap:wrap;}
              .ow-grid{display:grid;gap:10px;background:var(--panel2);padding:14px;border-radius:var(--radius);}
              .ow-heading{font-size:28px;font-weight:700;letter-spacing:.3px;margin:2px 0 6px;
                          background:linear-gradient(90deg,var(--accent),var(--accent2));
                          -webkit-background-clip:text;background-clip:text;color:transparent;}
              .ow-text{font-size:16px;color:var(--muted);}
              .ow-btn{appearance:none;border:0;cursor:pointer;font:inherit;font-weight:600;
                      color:#fff;background:linear-gradient(180deg,var(--accent),#4f46e5);
                      padding:11px 18px;border-radius:12px;transition:transform .08s ease,box-shadow .2s ease,filter .2s;
                      box-shadow:0 6px 16px rgba(99,102,241,.35);}
              .ow-btn:hover{filter:brightness(1.08);box-shadow:0 10px 22px rgba(99,102,241,.45);}
              .ow-btn:active{transform:translateY(1px) scale(.98);}
              .ow-disc{width:52px;height:52px;border-radius:50%;background:var(--empty);
                       box-shadow:inset 0 3px 8px rgba(0,0,0,.45);transition:background .25s ease, transform .25s ease;}
              .ow-disc.red{background:radial-gradient(circle at 35% 30%, #ff7a7a, var(--red));transform:scale(1.02);}
              .ow-disc.yellow{background:radial-gradient(circle at 35% 30%, #ffe08a, var(--yellow));transform:scale(1.02);}
            </style>
            </head>
            <body>
              <div id="app">loading…</div>
            <script>
              let version = -1;
              const app = document.getElementById('app');
              const KNOWN = {empty:1, red:1, yellow:1};

              function build(node){
                if(node == null) return document.createTextNode('');
                const t = node.type;
                if(t === 'text'){ const d=document.createElement('div'); d.className='ow-text'; d.textContent=node.text; return d; }
                if(t === 'heading'){ const d=document.createElement('div'); d.className='ow-heading'; d.textContent=node.text; return d; }
                if(t === 'button'){
                  const b=document.createElement('button'); b.className='ow-btn'; b.textContent=node.label;
                  b.addEventListener('click',()=>fire(node.id)); return b;
                }
                if(t === 'disc'){
                  const d=document.createElement('div'); const c=(node.color||'').toLowerCase();
                  d.className='ow-disc'+(KNOWN[c]?(' '+c):''); if(!KNOWN[c]&&c) d.style.background=node.color; return d;
                }
                if(t === 'row' || t === 'col'){
                  const d=document.createElement('div'); d.className = t==='row'?'ow-row':'ow-col';
                  (node.children||[]).forEach(ch=>d.appendChild(build(ch))); return d;
                }
                if(t === 'grid'){
                  const d=document.createElement('div'); d.className='ow-grid';
                  d.style.gridTemplateColumns='repeat('+(node.cols||1)+', auto)';
                  (node.children||[]).forEach(ch=>d.appendChild(build(ch))); return d;
                }
                const d=document.createElement('div'); d.textContent=JSON.stringify(node); return d;
              }

              function render(tree){ app.innerHTML=''; app.appendChild(build(tree)); }

              async function fire(id){
                if(!id) return;
                try{ await fetch('/event?id='+encodeURIComponent(id), {method:'POST'}); }catch(e){}
                sync(true);
              }
              async function sync(force){
                try{
                  const r = await fetch('/state'); const s = await r.json();
                  if(force || s.version !== version){ version = s.version; render(s.tree); }
                }catch(e){}
              }
              sync(true);
              setInterval(()=>sync(false), 300);
            </script>
            </body>
            </html>
            """;
}
