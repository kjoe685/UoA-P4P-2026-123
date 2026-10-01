package engine.application;

import engine.TestFixtures;
import engine.chat.*;
import engine.transcript.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RunLifecycleTest {
    @TempDir Path root;
    static ChatResponse completed(String text) { return new ChatResponse(text,"demo","demo",ChatResponse.CompletionStatus.COMPLETED,null,0); }
    static void finish(RunSession session) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        int next=0;
        while (!session.isFinished() && System.nanoTime()<deadline) next+=session.awaitEvents(next,100).size();
        assertTrue(session.isFinished(),"Run timed out");
    }
    @Test void privateSentinelsStayInRecipientSetupAndNeverEnterPublicEvidence() throws Exception {
        TestFixtures.copyResources(root);
        List<ChatRequest> requests=new CopyOnWriteArrayList<>();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")), model -> request -> {
            requests.add(request); return completed("A public argument.");
        })) {
            var session=app.start(TestFixtures.settings()); finish(session);
            assertTrue(requests.size()>=8);
            for (var request:requests) {
                String messages=Json.write(request.messages());
                assertFalse(messages.contains("STRAW_MAN")); assertFalse(messages.contains("private directive"));
                assertFalse(messages.contains("config/engine"));
                assertFalse(messages.contains("calling"));
                if (request.systemInstructions().contains("Labour")) assertFalse(request.systemInstructions().contains("Straw man"));
            }
            String transcript=Json.write(session.transcript());
            for (String forbidden:List.of("strategy","systemPrompt","sourceContents","calling","STRAW_MAN","modelPreset")) assertFalse(transcript.contains(forbidden));
            assertEquals(2,session.transcript().roster().size());
            assertEquals(List.of("topic-1","topic-2"),session.transcript().topics().stream().map(Topic::id).toList());
            assertEquals(session.transcript(),Json.read(transcript,Transcript.class));
            assertFalse(app.textExport(session.id()).contains("STRAW_MAN"));
        }
    }
    @Test void resourcesReloadForNextRunAndActiveRunKeepsOriginalSnapshot() throws Exception {
        TestFixtures.copyResources(root);
        Path base=root.resolve("prompts/BasePrompt.txt"); Files.writeString(base,Files.readString(base)+"\nOLD_SENTINEL");
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        List<ChatRequest> requests=new CopyOnWriteArrayList<>();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")), model -> request -> {
            requests.add(request); entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return completed("Public argument.");
        })) {
            var first=app.start(TestFixtures.settings()); assertTrue(entered.await(5,TimeUnit.SECONDS));
            Files.writeString(base,Files.readString(base).replace("OLD_SENTINEL","NEW_SENTINEL"));
            release.countDown(); finish(first);
            assertTrue(requests.stream().allMatch(request -> request.systemInstructions().contains("OLD_SENTINEL")));
            requests.clear(); var second=app.start(TestFixtures.settings()); finish(second);
            assertTrue(requests.stream().allMatch(request -> request.systemInstructions().contains("NEW_SENTINEL")));
            assertFalse(Json.write(first.transcript()).contains("SENTINEL"));
            var setup=new RunStore(root.resolve("runs")).setup(first.id());
            assertTrue(setup.sourceContents().get("prompts/BasePrompt.txt").contains("OLD_SENTINEL"));
        }
    }
    @Test void cancelledRunKeepsCompletedEvidenceAcrossRestartAndMakesNoFurtherCalls() throws Exception {
        TestFixtures.copyResources(root);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger(); String id;
        var store=new RunStore(root.resolve("runs"));
        try (var app=new DebateApplication(root,store,model -> request -> {
            int call=calls.incrementAndGet();
            if (call==2) {
                entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return completed(call==1 ? "Completed public speech." : "ABANDONED_SENTINEL");
        })) {
            var session=app.start(TestFixtures.settings()); id=session.id(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            app.cancel(id); release.countDown(); finish(session);
            assertEquals(Transcript.Outcome.ADJOURNED,session.transcript().outcome());
            assertTrue(Json.write(session.transcript()).contains("Completed public speech"));
            assertFalse(Json.write(session.transcript()).contains("ABANDONED_SENTINEL"));
        }
        int before=calls.get();
        try (var restored=new DebateApplication(root,store,model -> request -> { calls.incrementAndGet(); return completed("Unexpected"); })) {
            assertEquals(before,calls.get());
            assertEquals(Transcript.Outcome.ADJOURNED,restored.find(id).transcript().outcome());
            assertEquals(store.transcript(id),restored.find(id).transcript());
        }
    }
    @Test void failuresRetainEarlierSpeechesAndNeverStreamExceptionSecrets() throws Exception {
        TestFixtures.copyResources(root); AtomicInteger calls=new AtomicInteger();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> {
            if (calls.incrementAndGet()>1) throw new IllegalStateException("PRIVATE_PROVIDER_SENTINEL",new RuntimeException("API_KEY_SENTINEL"));
            return completed("Completed evidence.");
        })) {
            var run=app.start(TestFixtures.settings()); finish(run);
            assertEquals(Transcript.Outcome.ERROR,run.transcript().outcome());
            String view=String.join("",run.awaitEvents(0,1));
            assertFalse(view.contains("SENTINEL")); assertTrue(view.contains("Generation failed"));
            assertTrue(app.textExport(run.id()).contains("Completed evidence"));
        }
    }
    @Test void importedTranscriptsAreAssignedNewStorageIdsAndDoNotGenerate() throws Exception {
        TestFixtures.copyResources(root); AtomicInteger calls=new AtomicInteger();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> { calls.incrementAndGet(); return completed("Public speech."); })) {
            var original=app.start(TestFixtures.settings()); finish(original); int before=calls.get();
            var imported=app.importTranscript(original.transcript());
            assertNotEquals(original.id(),imported.id()); assertEquals(original.transcript().events(),imported.transcript().events());
            assertEquals(before,calls.get()); assertTrue(imported.isFinished());
        }
    }
}
