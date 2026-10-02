package engine.application;

import com.sun.net.httpserver.HttpServer;
import engine.config.OllamaConfig;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuntimeUtf8Test {
    @TempDir Path root;
    private static byte[] tags(boolean cached) {
        return Json.write(Map.of("models",cached ? List.of(Map.of("name","fixture:latest","size",1,"digest","a".repeat(64))) : List.of())).getBytes(StandardCharsets.UTF_8);
    }
    private static OllamaConfig config(HttpServer server) { return new OllamaConfig(1,URI.create("http://localhost:"+server.getAddress().getPort()),16384,3,3); }
    @Test void malformedReadinessIsRefusedWithoutStartingOrExposingIgnoredFields() throws Exception {
        var bad=new AtomicReference<>("/api/version"); var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange -> {
            try (exchange) {
                String path=exchange.getRequestURI().getPath();
                byte[] body=path.equals("/api/version") ? "{\"version\":\"0.35.0\"}".getBytes(StandardCharsets.UTF_8) : tags(true);
                if (path.equals(bad.get())) body=ResourceEncodingTest.malformed(new String(body,StandardCharsets.UTF_8).replaceFirst("}$",",\"ignored\":\"PRIVATE_RUNTIME_SENTINEL <BAD_UTF8>\"}"));
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            }
        }); server.start();
        try (var manager=new ManagedOllama(root)) {
            for (String path:List.of("/api/version","/api/tags")) {
                bad.set(path); var ready=manager.readiness(config(server)); assertEquals(false,ready.get("running")); assertFalse(Json.write(ready).contains("SENTINEL"));
                assertThrows(IllegalStateException.class,() -> manager.ensureRunning(config(server)));
            }
            bad.set(""); assertEquals(true,manager.readiness(config(server)).get("running"));
            assertFalse(java.nio.file.Files.exists(root.resolve(".runtime")));
        } finally { server.stop(0); }
    }
    @Test void malformedPullRecordFailsAndRetainsOnlyEarlierProgressAcrossRestart() throws Exception {
        var cached=new AtomicBoolean(); var pulls=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/",exchange -> {
            try (exchange) {
                String path=exchange.getRequestURI().getPath();
                if (path.equals("/api/pull")) {
                    exchange.getRequestBody().readAllBytes(); pulls.incrementAndGet(); cached.set(true);
                    exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write("{\"status\":\"receiving\",\"total\":10,\"completed\":1}\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().write(ResourceEncodingTest.malformed("{\"status\":\"success\",\"ignored\":\"PRIVATE_RUNTIME_SENTINEL <BAD_UTF8>\"}\n"));
                } else {
                    byte[] body=path.equals("/api/version") ? "{\"version\":\"0.35.0\"}".getBytes(StandardCharsets.UTF_8) : tags(cached.get());
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
                }
            }
        }); server.start(); String id; Object result;
        try (var manager=new ManagedOllama(root); var jobs=new JobService(root.resolve("jobs"))) {
            var job=ManagedOllamaTest.finish(jobs,ManagedOllamaTest.pull(jobs,manager,config(server),"fixture").id()); id=job.id(); result=job.result();
            assertEquals(BackgroundJob.State.FAILED,job.state()); assertNotNull(result); assertEquals(1,pulls.get());
            assertEquals(1L,((Map<?,?>)result).get("completedBytes")); assertFalse(Json.write(job).contains("SENTINEL"));
        } finally { server.stop(0); }
        try (var recovered=new JobService(root.resolve("jobs"))) {
            assertEquals(BackgroundJob.State.FAILED,recovered.find(id).state()); assertEquals(Json.parse(Json.write(result)),recovered.find(id).result()); assertEquals(1,pulls.get());
        }
    }
}
