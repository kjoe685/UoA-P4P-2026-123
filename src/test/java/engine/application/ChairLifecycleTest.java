package engine.application;

import engine.TestFixtures;
import engine.chat.ChatRequest;
import engine.transcript.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChairLifecycleTest {
    @TempDir Path root;

    @Test void finalResponseRetainsAcceptedRulingsInEvidenceReplayExportAndRestart() throws Exception {
        terminalRulings(Transcript.Outcome.COMPLETE);
    }

    @Test void cancellationRetainsAcceptedRulingsButDiscardsUnfinishedSpeech() throws Exception {
        terminalRulings(Transcript.Outcome.ADJOURNED);
    }

    @Test void providerFailureRetainsAcceptedRulingsWithoutExceptionSecrets() throws Exception {
        terminalRulings(Transcript.Outcome.ERROR);
    }

    private void terminalRulings(Transcript.Outcome expected) throws Exception {
        TestFixtures.copyResources(root);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        var store=new RunStore(root.resolve("runs"));
        Transcript saved; String id;
        try (var app=new DebateApplication(root,store,model -> request -> {
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (expected==Transcript.Outcome.ERROR) throw new IllegalStateException("PRIVATE_PROVIDER_SECRET");
            return RunLifecycleTest.completed("Final response.");
        })) {
            var run=app.start(Map.of("topics",List.of("Housing"),"rounds",1,"members",List.of(Map.of("party","LABOUR"))));
            id=run.id(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            app.ruling(id,"Return to the question."); app.ruling(id,"Address the public evidence.");
            if (expected==Transcript.Outcome.ADJOURNED) {
                app.cancel(id);
                assertThrows(IllegalStateException.class,() -> app.ruling(id,"Too late after adjournment."));
            }
            release.countDown(); RunLifecycleTest.finish(run);
            saved=run.transcript(); assertEquals(expected,saved.outcome());
            assertEquals(List.of("Return to the question.","Address the public evidence."),
                    saved.events().stream().filter(event -> event.type()==PublicEvent.Type.CHAIR_RULING).map(PublicEvent::text).toList());
            assertEquals(expected==Transcript.Outcome.COMPLETE ? 1 : 0,
                    saved.events().stream().filter(event -> event.type()==PublicEvent.Type.SPEECH).count());
            assertEquals(saved,store.transcript(id));
            String view=String.join("",run.awaitEvents(0,1));
            for (var event:saved.events()) assertEquals(1,run.awaitEvents(0,1).stream().filter(json -> json.contains("\"turnId\":\""+event.id()+"\"")).count());
            assertTrue(view.indexOf("Address the public evidence.")<view.indexOf("\"type\":\"adjourned\""));
            assertFalse(view.contains("PRIVATE_PROVIDER_SECRET"));
            assertTrue(app.textExport(id).contains("Return to the question."));
            assertTrue(app.textExport(id).contains("Address the public evidence."));
            assertThrows(IllegalStateException.class,() -> app.ruling(id,"Too late."));
        } finally { release.countDown(); }
        try (var restarted=new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not construct providers"); })) {
            assertEquals(saved,restarted.find(id).transcript());
            assertTrue(String.join("",restarted.find(id).awaitEvents(0,1)).contains("Address the public evidence."));
        }
    }

    @Test void queuedRulingIsPublicInputForTheNextTurn() throws Exception {
        nextTurnRuling(false,false);
    }

    @Test void queuedRulingKeepsItsTopicAndPrecedesTheNextTopic() throws Exception {
        nextTurnRuling(true,false);
    }

    @Test void queuedRulingReachesTheNextInterjection() throws Exception {
        nextTurnRuling(false,true);
    }

    private void nextTurnRuling(boolean nextTopic,boolean interjection) throws Exception {
        TestFixtures.copyResources(root);
        Path config=root.resolve("config/engine.json");
        String configured=Files.readString(config).replace("\"cooperativeChance\": 0.15","\"cooperativeChance\": "+(interjection ? "1" : "0"));
        Files.writeString(config,configured);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger(); List<ChatRequest> requests=new CopyOnWriteArrayList<>();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> {
            requests.add(request);
            if (calls.incrementAndGet()==1) {
                entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return RunLifecycleTest.completed("Public argument.");
        })) {
            var run=app.start(Map.of("topics",nextTopic ? List.of("Housing","Climate") : List.of("Housing"),"rounds",nextTopic || interjection ? 1 : 2,
                    "members",interjection ? List.of(Map.of("party","LABOUR"),Map.of("party","NATIONAL")) : List.of(Map.of("party","LABOUR"))));
            assertTrue(entered.await(5,TimeUnit.SECONDS)); app.ruling(run.id(),"PUBLIC_CHAIR_CUE");
            release.countDown(); RunLifecycleTest.finish(run);
            assertEquals(interjection ? 4 : 2,requests.size());
            assertFalse(Json.write(requests.get(0).messages()).contains("PUBLIC_CHAIR_CUE"));
            assertTrue(Json.write(requests.get(1).messages()).contains("PUBLIC_CHAIR_CUE"));
            var events=run.transcript().events();
            assertEquals(PublicEvent.Type.CHAIR_RULING,events.get(2).type()); assertEquals("topic-1",events.get(2).topicId());
            assertEquals(nextTopic ? PublicEvent.Type.TOPIC : interjection ? PublicEvent.Type.INTERJECTION : PublicEvent.Type.SPEECH,events.get(3).type());
            if (nextTopic) assertEquals("topic-2",events.get(3).topicId());
        } finally { release.countDown(); }
    }
}
