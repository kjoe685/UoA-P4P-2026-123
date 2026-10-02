package engine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CliTransportTest {
    @TempDir Path root;
    @Test void redirectedExportRefusesBodyAndPreservesAnExistingFile() throws Exception {
        Path export=root.resolve("existing.txt"); Files.writeString(export,"OWNER_CONTENT_SENTINEL");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try (exchange) { byte[] body="UNTRUSTED_REDIRECT_SENTINEL".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/");
                exchange.sendResponseHeaders(307,body.length); exchange.getResponseBody().write(body); }
        }); server.start();
        try {
            var output=new ByteArrayOutputStream(); var cli=new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner(""),new PrintStream(output));
            assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"export","fixture",export.toString()}));
            assertEquals("OWNER_CONTENT_SENTINEL",Files.readString(export)); assertFalse(output.toString().contains("SENTINEL"));
        } finally { server.stop(0); }
    }
    @Test void malformedErrorResponseIsSanitizedAndGuidedMenuReturnsToSelection() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try (exchange) { byte[] body="PRIVATE_BACKEND_SENTINEL".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,body.length); exchange.getResponseBody().write(body); }
        }); server.start();
        try {
            var output=new ByteArrayOutputStream(); var cli=new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner("2\n0\n"),new PrintStream(output));
            var error=assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"runs"}));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
            cli.menu(); assertFalse(output.toString().contains("SENTINEL")); assertTrue(output.toString().contains("0 Exit"));
        } finally { server.stop(0); }
    }
    @Test void successfulCreatedAcceptedAndExportBodiesPreserveUnicode() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); String path=exchange.getRequestURI().getPath();
                byte[] body="{\"id\":\"Tēnā 😀\"}".getBytes(StandardCharsets.UTF_8);
                int status=path.equals("/api/debates") ? 201 : path.equals("/api/ollama-setup") ? 202 : 200;
                exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body);
            }
        }); server.start();
        try {
            var output=new ByteArrayOutputStream(); var cli=new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner(""),new PrintStream(output,true,StandardCharsets.UTF_8));
            Path settings=root.resolve("settings.json"); Files.writeString(settings,"{}");
            cli.command(new String[]{"start",settings.toString()}); cli.command(new String[]{"ollama","setup"});
            Path export=root.resolve("nested exports/Unicode ā 😀.txt"); cli.command(new String[]{"export","fixture",export.toString()});
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Tēnā 😀")); assertTrue(Files.readString(export).contains("Tēnā 😀"));
            Files.writeString(export,"OWNER_SENTINEL"); cli.command(new String[]{"export","fixture",export.toString()});
            assertFalse(Files.readString(export).contains("OWNER_SENTINEL"));
            try (var files=Files.list(export.getParent())) { assertEquals(List.of(export),files.toList()); }
        } finally { server.stop(0); }
    }
    @Test void oversizedSuccessAndErrorResponsesPreserveExistingExport() throws Exception {
        var status=new AtomicInteger(200); Path export=root.resolve("existing.txt"); Files.writeString(export,"OWNER_SENTINEL");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status.get(),0); byte[] bytes=new byte[8192]; Arrays.fill(bytes,(byte)' ');
                int limit=status.get()==200 ? 64*1024*1024 : 64*1024;
                try { for (int n=0;n<=limit/bytes.length;n++) exchange.getResponseBody().write(bytes); }
                catch (IOException ignored) { }
            }
        }); server.start();
        try {
            var cli=new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner(""),new PrintStream(new ByteArrayOutputStream()));
            for (int code:List.of(200,500)) {
                status.set(code); var error=assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"export","fixture",export.toString()}));
                assertTrue(error.getMessage().contains("size limit")); assertNull(error.getCause());
                assertEquals("OWNER_SENTINEL",Files.readString(export));
            }
        } finally { server.stop(0); }
    }
    private void stalledRequest(boolean cancel) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var thread=new AtomicReference<Thread>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var worker=Executors.newSingleThreadExecutor();
        server.createContext("/",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); entered.countDown();
                try { release.await(8,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }); server.start();
        try {
            var cli=new Main("http://127.0.0.1:"+server.getAddress().getPort(),new Scanner(""),new PrintStream(new ByteArrayOutputStream()),Duration.ofSeconds(cancel ? 30 : 1));
            var future=worker.submit(() -> {
                thread.set(Thread.currentThread()); var error=assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"config"}));
                assertEquals(cancel,Thread.currentThread().isInterrupted()); assertNull(error.getCause()); return error;
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS)); if (cancel) thread.get().interrupt();
            assertTrue(future.get(3,TimeUnit.SECONDS).getMessage().contains(cancel ? "interrupted" : "timed out"));
            assertEquals("released",worker.submit(() -> "released").get(1,TimeUnit.SECONDS));
        } finally { release.countDown(); server.stop(0); worker.shutdownNow(); }
    }
    @Test void deadlineIncludesStalledResponseBody() throws Exception { stalledRequest(false); }
    @Test void interruptionClosesStalledBodyAndReleasesWorker() throws Exception { stalledRequest(true); }
}
