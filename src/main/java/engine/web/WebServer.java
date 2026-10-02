package engine.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import engine.ChatManager;
import engine.application.DebateApplication;
import engine.application.RunStore;
import engine.config.ModelConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entry point for the web frontend. Serves the static site in {@code web/} and the JSON API in {@link ApiHandler}.
 * Run from the repository root (like {@link engine.Main}, it reads {@code web/}, {@code resources/} and
 * {@code keys/} using relative paths).
 */
public class WebServer {

    private static final int DEFAULT_PORT = 8080;
    private static final Path WEB_ROOT = Path.of("web");

    // Only answer requests addressed to this machine, so a malicious page cannot use DNS rebinding
    // to reach the API (and spend the user's OpenAI credit).
    private static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8");

    public static void main(String[] args) throws IOException {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Port must be a number, e.g. java -cp out engine.web.WebServer 8080");
                return;
            }
        }

        if (!Files.isRegularFile(WEB_ROOT.resolve("index.html"))) {
            System.out.println("Could not find web/index.html. Run this from the repository root.");
            return;
        }

        OwnedBackend backend = startLocal(port,Path.of("."));
        HttpServer server = backend.server();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close, "parliament-shutdown"));
        System.out.println("=== AI-Based Virtual Parliament: web frontend ===");
        System.out.println("The House is open at http://localhost:" + server.getAddress().getPort());
        System.out.println("Press Ctrl+C to stop the server.");
        if (Boolean.getBoolean("parliament.openBrowser") && java.awt.Desktop.isDesktopSupported()) {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("http://localhost:" + server.getAddress().getPort())); }
            catch (IOException | UnsupportedOperationException e) { System.out.println("Open the address above in your browser."); }
        }
    }

    /**
     * Starts the server on the loopback interface only.
     *
     * @param chatManagerFactory creates a fresh {@link ChatManager} for each MP agent, given the API key
     */
    public static HttpServer start(int port, Function<ModelConfig, ChatManager> chatManagerFactory) throws IOException {
        return startOwned(port,() -> new DebateApplication(Path.of("."), new RunStore(Path.of("runs")), chatManagerFactory)).server();
    }

    public static HttpServer start(int port, DebateApplication application) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        try { return startBound(server,application); }
        catch (RuntimeException | Error e) { stopBound(server); throw e; }
    }

    /** Reserve the socket before recovery can read or rewrite durable application state. */
    public static OwnedBackend startLocal(int port,Path root) throws IOException {
        return startOwned(port,() -> DebateApplication.local(root));
    }

    static OwnedBackend startOwned(int port,Supplier<DebateApplication> factory) throws IOException {
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),0);
        DebateApplication application=null;
        boolean started=false;
        try {
            application=java.util.Objects.requireNonNull(factory.get());
            startBound(server,application); started=true; return new OwnedBackend(server,application);
        } catch (RuntimeException | Error e) {
            try { if (application!=null) application.close(); } finally { if (started) stop(server); else stopBound(server); }
            throw e;
        }
    }

    public static final class OwnedBackend implements AutoCloseable {
        private final HttpServer server;
        private final DebateApplication application;
        private boolean closed;
        private OwnedBackend(HttpServer server,DebateApplication application) { this.server=server; this.application=application; }
        public HttpServer server() { return server; }
        @Override public synchronized void close() {
            if (closed) return; closed=true;
            try { application.close(); } finally { stop(server); }
        }
    }

    private static void stop(HttpServer server) {
        server.stop(0);
        if (server.getExecutor() instanceof ExecutorService executor) executor.shutdownNow();
    }

    private static void stopBound(HttpServer server) {
        // Java17 closes its selector in the dispatcher. A bound server needs that
        // dispatcher started before stop can fully release its socket/resources.
        server.start(); stop(server);
    }

    private static HttpServer startBound(HttpServer server,DebateApplication application) {
        // Each open event stream holds a thread, so the pool must be able to grow.
        server.setExecutor(Executors.newCachedThreadPool());

        ApiHandler apiHandler = new ApiHandler(application);
        server.createContext("/api/", exchange -> guarded(exchange, apiHandler));
        server.createContext("/", exchange -> guarded(exchange, WebServer::serveStaticFile));
        server.start();
        return server;
    }

    private static void guarded(HttpExchange exchange, HttpHandler handler) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        String hostName = host == null ? "" : host.replaceFirst(":\\d+$", "").toLowerCase();
        if (!ALLOWED_HOSTS.contains(hostName)) {
            sendText(exchange, 403, "Forbidden: open this app via http://localhost");
            return;
        }
        handler.handle(exchange);
    }

    private static void serveStaticFile(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }

        String requestPath = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        if (requestPath.endsWith("/")) {
            requestPath += "index.html";
        }
        Path root = WEB_ROOT.toAbsolutePath().normalize();
        Path file;
        try {
            file = root.resolve(requestPath.substring(1)).normalize();
        } catch (InvalidPathException e) {
            file = null;
        }
        if (file == null || !file.startsWith(root) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "Not found");
            return;
        }

        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type",
                CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
