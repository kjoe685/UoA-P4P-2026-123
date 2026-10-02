package engine;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CliEventTransportTest {
    @FunctionalInterface interface Handler { void serve(HttpExchange exchange) throws Exception; }
    private static final class Wire implements AutoCloseable {
        private final HttpServer server;
        Wire(Handler handler) throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                try (exchange) { handler.serve(exchange); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception ignored) { /* Fixture client can close a refused body. */ }
            }); server.start();
        }
        Main cli(OutputStream output,Duration timeout) {
            return cli(output,timeout,"");
        }
        Main cli(OutputStream output,Duration timeout,String input) {
            return new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner(input),
                    new PrintStream(output,true,StandardCharsets.UTF_8),timeout);
        }
        @Override public void close() { server.stop(0); }
    }
    private static void headers(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200,0);
    }
    @Test void malformedUtf8JsonAndSurrogateEventsAreRefusedWithoutPrintingInput() throws Exception {
        byte[] invalid="data: {\"text\":\"INPUT_SECRET_SENTINEL X(\"}\n\n".getBytes(StandardCharsets.UTF_8);
        for (int i=0;i<invalid.length;i++) if (invalid[i]=='X') { invalid[i]=(byte)0xc3; break; }
        for (byte[] body:List.of(invalid,"data: {\"text\":\"INPUT_SECRET_SENTINEL \\ud800\"}\n\n".getBytes(StandardCharsets.UTF_8),
                "data: INPUT_SECRET_SENTINEL\n\n".getBytes(StandardCharsets.UTF_8))) {
            try (var wire=new Wire(exchange -> { headers(exchange); exchange.getResponseBody().write(body); })) {
                var output=new ByteArrayOutputStream();
                var error=assertThrows(IllegalStateException.class,() -> wire.cli(output,Duration.ofSeconds(5)).command(new String[]{"watch","fixture"}));
                assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
            }
        }
    }
    @Test void oversizedLineIsBoundedBeforeOutput() throws Exception {
        try (var wire=new Wire(exchange -> {
            headers(exchange); exchange.getResponseBody().write("data: ".getBytes(StandardCharsets.UTF_8));
            byte[] bytes=new byte[8192]; Arrays.fill(bytes,(byte)'x');
            for (int n=0;n<=16*1024*1024/bytes.length;n++) exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().write('\n');
        })) {
            var error=assertThrows(IllegalStateException.class,() -> wire.cli(OutputStream.nullOutputStream(),Duration.ofSeconds(5)).command(new String[]{"watch","fixture"}));
            assertTrue(error.getMessage().contains("size limit")); assertNull(error.getCause());
        }
    }
    @Test void wrongContentTypeCannotPrintAnUntrustedBody() throws Exception {
        try (var wire=new Wire(exchange -> {
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,0);
            exchange.getResponseBody().write("data: {\"private\":\"INPUT_SECRET_SENTINEL\"}\n\n".getBytes(StandardCharsets.UTF_8));
        })) {
            var output=new ByteArrayOutputStream();
            assertThrows(IllegalStateException.class,() -> wire.cli(output,Duration.ofSeconds(5)).command(new String[]{"watch","fixture"}));
            assertFalse(output.toString().contains("SENTINEL"));
            wire.cli(output,Duration.ofSeconds(5),"3\nfixture\n0\n").menu();
            assertTrue(output.toString().contains("Cannot read sitting events"));
            assertTrue(output.toString().contains("0 Exit")); assertFalse(output.toString().contains("SENTINEL"));
        }
    }
    @Test void headerDeadlineDoesNotWaitForeverBeforeTheStreamStarts() throws Exception {
        stalled(true);
    }
    @Test void interruptionClosesAStalledStreamAndReleasesItsWorker() throws Exception {
        stalled(false);
    }
    private static void stalled(boolean beforeHeaders) throws Exception {
        var entered=new CountDownLatch(1); var printed=new CountDownLatch(1); var release=new CountDownLatch(1); var thread=new AtomicReference<Thread>();
        var worker=Executors.newSingleThreadExecutor();
        try (var wire=new Wire(exchange -> {
            if (!beforeHeaders) {
                headers(exchange); exchange.getResponseBody().write("data: {\"type\":\"ready\"}\n\n:".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            entered.countDown(); release.await(8,TimeUnit.SECONDS);
            if (beforeHeaders) headers(exchange);
        })) {
            var cli=wire.cli(new OutputStream() {
                @Override public void write(int value) { if (value=='\n') printed.countDown(); }
            },Duration.ofSeconds(beforeHeaders ? 1 : 30));
            var future=worker.submit(() -> {
                thread.set(Thread.currentThread());
                var error=assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"watch","fixture"}));
                assertEquals(!beforeHeaders,Thread.currentThread().isInterrupted()); assertNull(error.getCause()); return error;
            });
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                if (!beforeHeaders) { assertTrue(printed.await(3,TimeUnit.SECONDS)); thread.get().interrupt(); }
                assertTrue(future.get(3,TimeUnit.SECONDS).getMessage().contains(beforeHeaders ? "timed out" : "interrupted"));
                assertEquals("released",worker.submit(() -> "released").get(1,TimeUnit.SECONDS));
            } finally { release.countDown(); }
        } finally { release.countDown(); worker.shutdownNow(); worker.awaitTermination(3,TimeUnit.SECONDS); }
    }
    @Test void validUnicodeAndKeepAlivesContinueBeyondTheHeaderDeadline() throws Exception {
        String first="{\"text\":\"Tēnā 😀 e\u0301 \ufffd\"}",second="{\"type\":\"done\"}";
        try (var wire=new Wire(exchange -> {
            headers(exchange);
            exchange.getResponseBody().write(("id: 0\ndata: "+first+"\n\n: keep-alive\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush(); Thread.sleep(1100);
            exchange.getResponseBody().write(("id: 1\r\ndata: "+second+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        })) {
            var output=new ByteArrayOutputStream(); var cli=wire.cli(output,Duration.ofSeconds(1));
            cli.command(new String[]{"watch","fixture"});
            assertEquals(first+System.lineSeparator()+second+System.lineSeparator(),output.toString(StandardCharsets.UTF_8));
        }
    }
}
