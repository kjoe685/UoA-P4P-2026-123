package engine.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import engine.evaluation.local.HttpNlpClient;
import engine.evaluation.local.NlpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback transport, with no model calls. Stalls begin after response headers. */
class HttpTransportTest {
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final NlpRequest NLP = new NlpRequest(2,List.of("vader-sentiment"),List.of());
    private static Object call(String kind,URI endpoint,int seconds) {
        if (kind.equals("provider")) return ProviderHttp.post(ProviderHttp.client(),endpoint,
                Map.of("Authorization","Bearer KEY_SENTINEL"),Map.of("prompt","PRIVATE_SENTINEL"),seconds,"Test provider");
        return new HttpNlpClient(endpoint,Duration.ofSeconds(seconds)).analyze(NLP);
    }
    private static void safeError(IllegalStateException error) {
        assertNull(error.getCause());
        for (String secret:List.of("KEY_SENTINEL","PRIVATE_SENTINEL","RESPONSE_SENTINEL"))
            assertFalse(error.getMessage().contains(secret));
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService handlers=Executors.newCachedThreadPool(task -> {
            var thread=new Thread(task,"fake-http-handler"); thread.setDaemon(true); return thread;
        });
        Server(HttpHandler handler) throws IOException {
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.setExecutor(handlers); http.createContext("/",handler); http.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/"); }
        @Override public void close() { http.stop(0); handlers.shutdownNow(); }
    }
    private static void stall(HttpExchange exchange,int status,CountDownLatch entered,CountDownLatch release) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status,0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); entered.countDown();
            try { release.await(10,TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { exchange.getResponseBody().write('}'); } catch (IOException ignored) { }
        }
    }
    @ParameterizedTest @ValueSource(strings={"provider","nlp"})
    void rejectsOversizedSuccessBeforeJsonParsing(String kind) throws Exception {
        try (var server=new Server(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write("{\"schemaVersion\":2,\"methods\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] spaces=new byte[8192]; Arrays.fill(spaces,(byte)' ');
                try { for (int n=0;n<=LIMIT/spaces.length;n++) exchange.getResponseBody().write(spaces); }
                catch (IOException ignored) { /* Expected when the bounded client closes early. */ }
            }
        })) {
            var error=assertThrows(IllegalStateException.class,() -> call(kind,server.uri(),5));
            safeError(error); assertTrue(error.getMessage().contains("exceeds 16 MiB"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"provider","nlp"})
    void timesOutDuringBodyAfterReceivingHeaders(String kind) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try (var server=new Server(exchange -> stall(exchange,200,entered,release))) {
            var future=worker.submit(() -> assertThrows(IllegalStateException.class,() -> call(kind,server.uri(),1)));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var error=future.get(3,TimeUnit.SECONDS); safeError(error);
            assertTrue(error.getMessage().contains("timed out"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"provider","nlp"})
    void cancellationReleasesBodyReadAndPreservesInterrupt(String kind) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var thread=new AtomicReference<Thread>(); var worker=Executors.newSingleThreadExecutor();
        try (var server=new Server(exchange -> stall(exchange,200,entered,release))) {
            var future=worker.submit(() -> {
                thread.set(Thread.currentThread());
                var error=assertThrows(IllegalStateException.class,() -> call(kind,server.uri(),30));
                safeError(error); assertTrue(Thread.currentThread().isInterrupted());
                return error;
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS)); thread.get().interrupt();
            assertTrue(future.get(3,TimeUnit.SECONDS).getMessage().contains("interrupted"));
            assertEquals("worker released",worker.submit(() -> "worker released").get(1,TimeUnit.SECONDS));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"provider","nlp"})
    void rejectsErrorStatusWithoutWaitingForUntrustedBody(String kind) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try (var server=new Server(exchange -> stall(exchange,401,entered,release))) {
            var future=worker.submit(() -> assertThrows(IllegalStateException.class,() -> call(kind,server.uri(),10)));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var error=future.get(2,TimeUnit.SECONDS); safeError(error);
            assertTrue(error.getMessage().contains("HTTP 401"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void retriesOneRateLimitWithoutReadingItsStalledBody() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var calls=new AtomicInteger(); var worker=Executors.newSingleThreadExecutor();
        try (var server=new Server(exchange -> {
            if (calls.incrementAndGet()==1) stall(exchange,429,entered,release);
            else try (exchange) {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,2);
                exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        })) {
            var future=worker.submit(() -> call("provider",server.uri(),5));
            assertTrue(entered.await(3,TimeUnit.SECONDS)); assertEquals("{}",future.get(2,TimeUnit.SECONDS));
            assertEquals(2,calls.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"provider","nlp"})
    void redirectCannotForwardInputsOrCredentials(String kind) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var forwarded=new AtomicInteger(); var worker=Executors.newSingleThreadExecutor();
        try (var destination=new Server(exchange -> { forwarded.incrementAndGet(); exchange.close(); });
             var server=new Server(exchange -> {
                 exchange.getResponseHeaders().set("Location",destination.uri().toString());
                 stall(exchange,307,entered,release);
             })) {
            var future=worker.submit(() -> assertThrows(IllegalStateException.class,() -> call(kind,server.uri(),10)));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var error=future.get(2,TimeUnit.SECONDS); safeError(error);
            assertTrue(error.getMessage().contains("HTTP 307")); assertEquals(0,forwarded.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
