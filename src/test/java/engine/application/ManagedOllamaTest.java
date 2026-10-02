package engine.application;

import engine.*;
import engine.config.*;
import engine.utils.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedOllamaTest {
    @TempDir Path root;
    @Test void readinessNeverInstallsPullsOrGeneratesAndExternalServiceIsNeverOwnedOrStopped() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            assertEquals(false,manager.readiness(fixture.config()).get("running")); assertFalse(Files.exists(root.resolve(".runtime")));
            fixture.startService(); fixture.cache("qwen3:8b",true); fixture.cache("remote-alias:latest",false);
            var ready=manager.readiness(fixture.config()); assertEquals(true,ready.get("running")); assertEquals(false,ready.get("owned"));
            assertFalse(Json.write(ready).contains("REMOTE_SECRET_SENTINEL")); manager.ensureRunning(fixture.config());
            manager.requireCachedModel(fixture.config(),"qwen3:8b");
            assertThrows(IllegalStateException.class,() -> manager.requireCachedModel(fixture.config(),"missing"));
            assertThrows(IllegalStateException.class,() -> manager.requireCachedModel(fixture.config(),"remote-alias"));
            assertThrows(IllegalArgumentException.class,() -> manager.requireCachedModel(fixture.config(),"model:cloud"));
            manager.close(); assertEquals(true,manager.readiness(fixture.config()).get("running"));
            assertEquals(0,fixture.downloads.get()); assertEquals(0,fixture.starts.get()); assertEquals(0,fixture.pulls.get()); assertEquals(0,fixture.chats.get());
        }
    }
    @Test void installedRuntimeStartsOnceWithLocalEnvironmentAndClosesOnlyOwnedService() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root); var jobs=new JobService(root.resolve("jobs"))) {
            var install=jobs.submit("fixture-runtime",null,Map.of(),context -> { manager.install(fixture.config(),context); manager.ensureRunning(fixture.config()); return true; });
            assertEquals(BackgroundJob.State.COMPLETE,finish(jobs,install.id()).state());
            manager.ensureRunning(fixture.config()); assertEquals(1,fixture.starts.get()); assertEquals(1,fixture.downloads.get());
            assertEquals(true,manager.readiness(fixture.config()).get("owned"));
            assertEquals("1",fixture.builder.environment().get("OLLAMA_NO_CLOUD")); assertEquals("1",fixture.builder.environment().get("OLLAMA_NUM_PARALLEL"));
            assertEquals("false",fixture.builder.environment().get("OLLAMA_DEBUG_LOG_REQUESTS"));
            assertEquals(root.resolve(".runtime/ollama-models").toString(),fixture.builder.environment().get("OLLAMA_MODELS"));
            for (String key:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","OLLAMA_API_KEY","HF_TOKEN")) assertFalse(fixture.builder.environment().containsKey(key));
            manager.close(); assertEquals(false,manager.readiness(fixture.config()).get("running"));
        }
    }
    @Test void incompatibleOccupiedPortIsRefusedWithoutStartingOrChangingExternalService() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            fixture.incompatible=true; fixture.startService();
            var error=assertThrows(IllegalStateException.class,() -> manager.ensureRunning(fixture.config()));
            assertTrue(error.getMessage().contains("occupied")); assertEquals(0,fixture.starts.get()); assertFalse(Files.exists(root.resolve(".runtime")));
            assertFalse(Json.write(manager.readiness(fixture.config())).contains("BODY_SECRET_SENTINEL"));
        }
    }
    @Test void explicitPullSanitizesProgressReusesCacheAndRejectsRemoteAliasesAndPartialSuccess() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root); var jobs=new JobService(root.resolve("jobs"))) {
            fixture.startService();
            var job=pull(jobs,manager,fixture.config(),"qwen3:8b"); assertEquals(BackgroundJob.State.COMPLETE,finish(jobs,job.id()).state());
            assertEquals(Map.of("model","qwen3:8b","stream",true),fixture.requests.get(0));
            assertFalse(Files.readString(root.resolve("jobs/"+job.id()+"/job.json")).contains("SENTINEL")); assertEquals(0,fixture.chats.get());
            assertEquals(BackgroundJob.State.COMPLETE,finish(jobs,pull(jobs,manager,fixture.config(),"qwen3:8b").id()).state()); assertEquals(1,fixture.pulls.get());
            fixture.cache("alias:latest",false);
            assertEquals(BackgroundJob.State.FAILED,finish(jobs,pull(jobs,manager,fixture.config(),"alias").id()).state()); assertEquals(1,fixture.pulls.get());
            fixture.incompletePull=true; assertEquals(BackgroundJob.State.FAILED,finish(jobs,pull(jobs,manager,fixture.config(),"incomplete").id()).state());
            fixture.incompletePull=false; fixture.badPull=true;
            var bad=finish(jobs,pull(jobs,manager,fixture.config(),"failed").id()); assertEquals(BackgroundJob.State.FAILED,bad.state()); assertFalse(Json.write(bad).contains("SENTINEL"));
        }
    }
    @Test void cancellationAndBodyDeadlineReleaseStalledPullAndPreserveProgressAcrossRestart() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            fixture.startService(); fixture.stall=new CountDownLatch(1); String id;
            try (var jobs=new JobService(root.resolve("jobs"))) {
                var job=pull(jobs,manager,fixture.config(),"blocked"); id=job.id(); waitForProgress(jobs,id);
                jobs.cancel(id); assertEquals(BackgroundJob.State.CANCELLED,jobs.find(id).state()); assertNotNull(jobs.find(id).result());
                // Queued work proves the previous body read actually released the bounded worker.
                var next=jobs.submit("after-cancel",null,Map.of(),context -> true); assertEquals(BackgroundJob.State.COMPLETE,finish(jobs,next.id()).state());
            }
            try (var recovered=new JobService(root.resolve("jobs"))) { assertEquals(BackgroundJob.State.CANCELLED,recovered.find(id).state()); assertNotNull(recovered.find(id).result()); }
            fixture.stall.countDown(); fixture.stall=new CountDownLatch(1);
            try (var jobs=new JobService(root.resolve("timeout-jobs"))) {
                var config=new OllamaConfig(1,fixture.config().baseUrl(),16384,3,1);
                assertEquals(BackgroundJob.State.FAILED,finish(jobs,pull(jobs,manager,config,"timeout").id()).state());
                var next=jobs.submit("after-timeout",null,Map.of(),context -> true); assertEquals(BackgroundJob.State.COMPLETE,finish(jobs,next.id()).state());
            }
        }
    }
    @Test void malformedByteCountsAreRejectedEvenBetweenThrottledProgressWrites() throws Exception {
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root); var jobs=new JobService(root.resolve("jobs"))) {
            fixture.startService(); fixture.additionalProgress="{\"status\":\"receiving\",\"total\":100,\"completed\":101}\n";
            assertEquals(BackgroundJob.State.FAILED,finish(jobs,pull(jobs,manager,fixture.config(),"invalid-counts").id()).state());
        }
    }
    @Test void debateEvaluationAndQueuedSetupFreezeSettingsWithoutSendingRuntimeMetadataToModels() throws Exception {
        TestFixtures.copyResources(root);
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            Path config=root.resolve("config/ollama.json"); Files.writeString(config,Json.write(fixture.config()));
            var captured=new CopyOnWriteArrayList<OllamaConfig>(); var requests=new CopyOnWriteArrayList<engine.chat.ChatRequest>();
            var resources=engine.evaluation.llm.LlmEvaluationResources.load(root);
            try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),(model,runtime) -> {
                captured.add(runtime); return request -> { requests.add(request); return model.provider().equals("demo") ? RunLifecycleTest.completed("Public speech.") : LlmEvaluationTest.fake(request,resources.rubric()); };
            },null,manager)) {
                var run=app.start(TestFixtures.settings()); RunLifecycleTest.finish(run);
                assertTrue(captured.stream().allMatch(fixture.config()::equals));
                assertEquals(Json.write(fixture.config()),app.configuration().sourceContents().get("selection/ollama-settings.json"));
                assertFalse(Json.write(run.transcript()).contains("localRuntime"));
                CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
                var blocker=app.background().submit("blocker",null,Map.of(),context -> { entered.countDown(); release.await(); return true; }); assertTrue(entered.await(5,TimeUnit.SECONDS));
                var imported=app.importTranscript(LlmEvaluationTest.evidence()); var evaluation=app.evaluateLlm(imported.id(),Map.of("modelPreset","qwen3-local"));
                fixture.startService(); var download=app.downloadOllamaModel("qwen3-local");
                Files.writeString(config,Json.write(OllamaConfig.defaults()));
                Path presets=root.resolve("config/engine.json"); Files.writeString(presets,Files.readString(presets).replace("qwen3:8b","changed:latest"));
                release.countDown(); assertEquals(BackgroundJob.State.COMPLETE,finish(app.background(),blocker.id()).state());
                assertEquals(BackgroundJob.State.COMPLETE,finish(app.background(),evaluation.id()).state()); assertEquals(BackgroundJob.State.COMPLETE,finish(app.background(),download.id()).state());
                assertTrue(captured.stream().allMatch(fixture.config()::equals)); assertTrue(fixture.models.containsKey("qwen3:8b")); assertFalse(fixture.models.containsKey("changed:latest"));
                for (var request:requests) assertFalse(Json.write(request).contains("localRuntime"));
                String frozen=Files.readString(root.resolve("runs/jobs/"+evaluation.id()+"/input.json"));
                assertTrue(frozen.contains(fixture.config().baseUrl().toString())); assertFalse(frozen.contains("resolvedPrompts")); assertFalse(frozen.contains("sourceContents"));
            }
        }
    }
    static BackgroundJob pull(JobService jobs,ManagedOllama manager,OllamaConfig config,String model) {
        return jobs.submit("pull",null,Map.of("model",model),context -> { manager.download(config,model,context); return true; });
    }
    static BackgroundJob finish(JobService jobs,String id) throws Exception { return LlmEvaluationTest.finish(jobs,id); }
    static void waitForProgress(JobService jobs,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!jobs.find(id).progress().equals("Receiving local model files") && !jobs.find(id).terminal() && System.nanoTime()<deadline) Thread.sleep(10);
        assertEquals("Receiving local model files",jobs.find(id).progress()); assertNotNull(jobs.find(id).result());
    }
}
