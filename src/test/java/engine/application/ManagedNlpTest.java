package engine.application;

import com.sun.net.httpserver.HttpServer;
import engine.TestFixtures;
import engine.evaluation.local.LocalEvaluationConfig;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ManagedNlpTest {
    @TempDir Path root;
    private static LocalEvaluationConfig config(int port) {
        return new LocalEvaluationConfig(1,URI.create("http://127.0.0.1:"+port+"/v1/analyze"),5,1,List.of("vader-sentiment"));
    }
    private static Map<String,Object> health(String settings) {
        return Map.of("status","ok","schemaVersion",2,"service","parliament-nlp","implementationVersion",engine.evaluation.local.NlpResponse.IMPLEMENTATION_VERSION,
                "configurationSha256",Hashes.sha256(settings),"methods",List.of("vader-sentiment"),"readiness",Map.of());
    }
    @Test void readinessRejectsUnrecognizedFieldsInsteadOfSavingServiceSecrets() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json"));
        var reply=new HashMap<String,Object>(health(settings)); reply.put("apiKey","KEY_SECRET_SENTINEL");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/health",exchange -> {
            try (exchange) { byte[] body=Json.write(reply).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); }
        }); server.start();
        try (var nlp=new ManagedNlp(root)) {
            var ready=nlp.readiness(config(server.getAddress().getPort()));
            assertEquals(false,ready.get("running")); assertFalse(Json.write(ready).contains("SENTINEL"));
        } finally { server.stop(0); }
    }
    @Test void oversizedReadinessIsRefusedBeforeParsingOrPublishing() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/health",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write(Json.write(health(settings)).getBytes(StandardCharsets.UTF_8));
                byte[] spaces=new byte[8192]; Arrays.fill(spaces,(byte)' ');
                try { for (int i=0;i<=1_000_000/spaces.length;i++) exchange.getResponseBody().write(spaces); }
                catch (java.io.IOException ignored) { }
            }
        }); server.start();
        try (var nlp=new ManagedNlp(root)) {
            var ready=nlp.readiness(config(server.getAddress().getPort()));
            assertEquals(false,ready.get("running")); assertFalse(ready.containsKey("service"));
            assertFalse(Files.exists(root.resolve(".runtime")));
        } finally { server.stop(0); }
    }
    @Test void readinessDeadlineCoversBodyStallAfterHeaders() throws Exception {
        TestFixtures.copyResources(root); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var worker=Executors.newSingleThreadExecutor();
        server.createContext("/health",exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); entered.countDown();
                try { release.await(8,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }); server.start();
        try (var nlp=new ManagedNlp(root)) {
            var future=worker.submit(() -> nlp.readiness(config(server.getAddress().getPort())));
            assertTrue(entered.await(3,TimeUnit.SECONDS)); assertEquals(false,future.get(4,TimeUnit.SECONDS).get("running"));
        } finally { release.countDown(); server.stop(0); worker.shutdownNow(); }
    }
    @Test void queuedBaseSetupPersistsCapturedConfigurationWithoutExecutingDependencies() throws Exception {
        TestFixtures.copyResources(root); var release=new CountDownLatch(1); var entered=new CountDownLatch(1);
        String settings=Files.readString(root.resolve("nlp/config/models.json"));
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("No inference"); return null; })) {
            var blocker=app.background().submit("fixture-blocker",null,Map.of(),context -> { entered.countDown(); release.await(); return true; });
            assertTrue(entered.await(3,TimeUnit.SECONDS)); var job=app.setupLocalNlp();
            try {
                var frozen=Json.read(Files.readString(root.resolve("runs/jobs/"+job.id()+"/input.json")),com.fasterxml.jackson.databind.JsonNode.class);
                assertEquals(settings,frozen.path("localModelSettings").asText());
                assertEquals(Json.parse(Files.readString(root.resolve("config/local-evaluation.json"))),Json.parse(frozen.path("configuration").toString()));
                Files.writeString(root.resolve("nlp/config/models.json"),settings+"\n");
                assertEquals(settings,Json.read(Files.readString(root.resolve("runs/jobs/"+job.id()+"/input.json")),com.fasterxml.jackson.databind.JsonNode.class).path("localModelSettings").asText());
            } finally { app.background().cancel(job.id()); app.background().cancel(blocker.id()); release.countDown(); }
        }
    }
    private Path fakeInstalledPython() throws Exception {
        Files.writeString(root.resolve("nlp/uv.lock"),"FIXTURE_LOCK_NEVER_INSTALLED");
        Path python=root.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? ".runtime/nlp-env/Scripts/python.exe" : ".runtime/nlp-env/bin/python");
        Files.createDirectories(python.getParent()); Files.writeString(python,"FIXTURE_EXECUTABLE_NEVER_RUN");
        AtomicFiles.write(root.resolve(".runtime/nlp/setup.complete"),Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock"))));
        return python;
    }
    private static class FakeProcess extends Process {
        volatile boolean alive; final Runnable stop;
        FakeProcess(boolean alive,Runnable stop) { this.alive=alive; this.stop=stop; }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() throws InterruptedException { while (alive) Thread.sleep(10); return 0; }
        public boolean waitFor(long timeout,TimeUnit unit) throws InterruptedException { if (alive) Thread.sleep(Math.min(unit.toMillis(timeout),100)); return !alive; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { alive=false; stop.run(); }
        public Process destroyForcibly() { destroy(); return this; }
        public boolean isAlive() { return alive; }
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
    @Test void queuedSetupStartsAndReportsItsSnapshotAfterEditsThenStopsOnlyOwnedChild() throws Exception {
        TestFixtures.copyResources(root); fakeInstalledPython();
        String settings=Files.readString(root.resolve("nlp/config/models.json"));
        int port; try (var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        var captured=config(port); Files.writeString(root.resolve("config/local-evaluation.json"),Json.write(captured));
        var release=new CountDownLatch(1); var entered=new CountDownLatch(1); var commands=new CopyOnWriteArrayList<List<String>>();
        var service=new AtomicReference<HttpServer>(); var child=new AtomicReference<FakeProcess>();
        var nlp=new ManagedNlp(root,builder -> {
            var command=builder.command(); commands.add(List.copyOf(command));
            if (!command.contains("serve")) return new FakeProcess(false,() -> { });
            String frozen=Files.readString(Path.of(command.get(command.indexOf("--config")+1)));
            assertEquals(settings,frozen); assertEquals(String.valueOf(port),command.get(command.indexOf("--port")+1));
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            server.createContext("/health",exchange -> {
                try (exchange) { byte[] body=Json.write(health(frozen)).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); }
            }); server.start(); service.set(server);
            var process=new FakeProcess(true,() -> server.stop(0)); child.set(process); return process;
        });
        String id;
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),(model,runtime) -> { fail("No inference"); return null; },null,new ManagedOllama(root),nlp)) {
            app.background().submit("fixture-blocker",null,Map.of(),context -> { entered.countDown(); release.await(); return true; });
            assertTrue(entered.await(3,TimeUnit.SECONDS)); var job=app.setupLocalNlp(); id=job.id();
            Files.writeString(root.resolve("nlp/config/models.json"),settings+"\n");
            Files.writeString(root.resolve("config/local-evaluation.json"),Json.write(config(port==65535 ? port-1 : port+1)));
            release.countDown(); job=LocalEvaluationTest.finish(app.background(),id);
            assertEquals(BackgroundJob.State.COMPLETE,job.state());
            var report=(Map<?,?>)job.result(); assertEquals(true,report.get("running"));
            assertEquals(Hashes.sha256(settings),((Map<?,?>)report.get("service")).get("configurationSha256"));
            assertEquals(2,commands.size()); assertTrue(child.get().isAlive());
        } finally { release.countDown(); if (service.get()!=null) service.get().stop(0); nlp.close(); }
        assertFalse(child.get().isAlive());
        try (var restarted=new JobService(root.resolve("runs/jobs"))) {
            assertEquals(BackgroundJob.State.COMPLETE,restarted.find(id).state());
            assertTrue(Json.write(restarted.find(id).result()).contains(Hashes.sha256(settings)));
        }
    }
    @Test void interruptionNeverStartsServiceAndValidationStopsItsOwnedProcess() throws Exception {
        TestFixtures.copyResources(root); fakeInstalledPython(); var starts=new AtomicInteger(); var child=new AtomicReference<FakeProcess>();
        try (var nlp=new ManagedNlp(root,builder -> { starts.incrementAndGet(); var process=new FakeProcess(true,() -> { }); child.set(process); return process; })) {
            String settings=Files.readString(root.resolve("nlp/config/models.json"));
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class,() -> nlp.ensureRunning(config(12345),settings)); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            assertEquals(0,starts.get()); var entered=new CountDownLatch(1); var thread=new AtomicReference<Thread>();
            var worker=Executors.newSingleThreadExecutor();
            try {
                var future=worker.submit(() -> {
                    thread.set(Thread.currentThread()); entered.countDown();
                    assertThrows(IllegalStateException.class,() -> nlp.validateConfiguration(settings));
                    assertTrue(Thread.currentThread().isInterrupted());
                });
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while (child.get()==null && System.nanoTime()<deadline) Thread.sleep(10);
                assertNotNull(child.get()); thread.get().interrupt(); future.get(3,TimeUnit.SECONDS);
                assertFalse(child.get().isAlive());
                try (var files=Files.list(root.resolve(".runtime/nlp"))) { assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("validate-"))); }
            } finally { worker.shutdownNow(); }
        }
    }
}
