package engine.web;

import engine.TestFixtures;
import engine.application.*;
import engine.chat.ChatResponse;
import engine.transcript.Transcript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BackendStartupTest {
    @TempDir Path root;
    @Test void occupiedPortRefusesBeforeFactoryAndFailedFactoryReleasesItsSocket() throws Exception {
        var calls=new AtomicInteger();
        try (var listener=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) {
            assertThrows(java.io.IOException.class,() -> WebServer.startOwned(listener.getLocalPort(),() -> { calls.incrementAndGet(); fail("Bind before factory"); return null; }));
            assertEquals(0,calls.get());
        }
        int port;
        try (var reservation=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { port=reservation.getLocalPort(); }
        var failure=new IllegalArgumentException("Synthetic configuration failure");
        assertSame(failure,assertThrows(IllegalArgumentException.class,() -> WebServer.startOwned(port,() -> { calls.incrementAndGet(); throw failure; })));
        assertEquals(1,calls.get());
        try (var retry=new ServerSocket(port,0,InetAddress.getLoopbackAddress())) { assertEquals(port,retry.getLocalPort()); }
        assertFalse(Files.exists(root.resolve("runs")));
    }
    @Test void normalOwnedStartupAndIdempotentCloseReleaseApplicationAndHttpResources() throws Exception {
        TestFixtures.copyResources(root); var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> new engine.demo.DemoChatManager());
        var calls=new AtomicInteger(); var owned=WebServer.startOwned(0,() -> { calls.incrementAndGet(); return app; });
        var server=owned.server(); int port=server.getAddress().getPort(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/config")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode()); assertTrue(response.body().contains("\"defaultProvider\":\"demo\"")); assertEquals(1,calls.get());
            var job=app.background().submit("synthetic-owned-lifecycle",null,Map.of(),context -> { entered.countDown(); release.await(); return true; });
            assertTrue(entered.await(5,TimeUnit.SECONDS)); owned.close(); owned.close();
            assertEquals(BackgroundJob.State.CANCELLED,app.background().find(job.id()).state()); assertTrue(((ExecutorService)server.getExecutor()).isShutdown());
            try (var retry=new ServerSocket(port,0,InetAddress.getLoopbackAddress())) { assertEquals(port,retry.getLocalPort()); }
        } finally { release.countDown(); owned.close(); }
    }
    @Test void duplicateBrowserLaunchCannotRecoverOrRewriteTheFirstBackendsActiveState() throws Exception {
        TestFixtures.copyResources(root); Files.createDirectories(root.resolve("web")); Files.writeString(root.resolve("web/index.html"),"Synthetic startup fixture");
        var generationEntered=new CountDownLatch(1); var jobEntered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> {
            generationEntered.countDown();
            try { release.await(30,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new ChatResponse("Synthetic startup contribution.","demo","fixed",ChatResponse.CompletionStatus.COMPLETED,null,0);
        })) {
            var run=app.start(Map.of("topics",List.of("Synthetic active sitting"),"rounds",1,"groundingCount",0,"members",List.of(Map.of("party","LABOUR"))));
            assertTrue(generationEntered.await(5,TimeUnit.SECONDS));
            var job=app.background().submit("synthetic-startup-fixture",null,Map.of(),context -> {
                context.update("Synthetic work in progress",Map.of("previous","retained")); jobEntered.countDown(); release.await(30,TimeUnit.SECONDS); return true;
            });
            assertTrue(jobEntered.await(5,TimeUnit.SECONDS)); var server=WebServer.start(0,app); Process child=null;
            try {
                var original=new LinkedHashMap<Path,byte[]>();
                for (String path:List.of("runs/"+run.id()+"/transcript.json","runs/"+run.id()+"/view.json","runs/jobs/"+job.id()+"/job.json")) {
                    Path file=root.resolve(path); original.put(file,Files.readAllBytes(file));
                }
                Path project=Path.of("").toAbsolutePath(); String classpath=project.resolve("target/classes")+java.io.File.pathSeparator+project.resolve("target/virtual-parliament.jar");
                boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",windows ? "java.exe" : "java").toString(),"-cp",classpath,
                        "engine.web.WebServer",String.valueOf(server.getAddress().getPort())).directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("duplicate-launch.log").toFile());
                for (String name:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","HF_TOKEN","HUGGING_FACE_HUB_TOKEN")) builder.environment().remove(name);
                child=builder.start(); assertTrue(child.waitFor(15,TimeUnit.SECONDS)); assertNotEquals(0,child.exitValue());
                for (var entry:original.entrySet()) assertArrayEquals(entry.getValue(),Files.readAllBytes(entry.getKey()),"Failed launch changed "+entry.getKey().getFileName());
                assertEquals(Transcript.Outcome.RUNNING,run.transcript().outcome()); assertEquals(BackgroundJob.State.RUNNING,app.background().find(job.id()).state());
                assertTrue(Files.readString(root.resolve("duplicate-launch.log")).contains("BindException"));
            } finally {
                if (child!=null && child.isAlive()) child.destroyForcibly(); release.countDown();
                server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow();
            }
        } finally { release.countDown(); }
    }
}
