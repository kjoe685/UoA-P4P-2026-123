package engine.application;

import engine.TestFixtures;
import engine.transcript.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @TempDir Path root;
    @Test void interruptedSavedRunIsMarkedWithoutResumingModelCallsAndCommittedEvidenceIsRecovered() throws Exception {
        var snapshot=TestFixtures.copyResources(root);
        String id=UUID.randomUUID().toString(); var store=new RunStore(root.resolve("runs"));
        var spec=RunSpec.resolve(TestFixtures.settings(),snapshot.config());
        var setup=new PrivateSetup(spec,snapshot.config(),snapshot.sourceContents(),snapshot.sourceHashes(),Map.of("labour","PRIVATE_SENTINEL"));
        var member=new Participant("labour","Labour MP",engine.agent.Party.LABOUR,"Labour");
        var event=new PublicEvent("turn-1","topic-1",PublicEvent.Type.SPEECH,member,"Saved before crash");
        store.create(id,setup);
        store.saveTranscript(new Transcript(2,id,System.currentTimeMillis(),null,List.of(member),spec.topics(),List.of(event),Transcript.Outcome.RUNNING));
        store.saveView(id,List.of(Json.write(Map.of("type","sitting","id",id))));
        AtomicInteger calls=new AtomicInteger();
        try (var app=new DebateApplication(root,store,model -> request -> { calls.incrementAndGet(); throw new AssertionError("Must not resume"); })) {
            var run=app.find(id);
            assertEquals(Transcript.Outcome.INTERRUPTED,run.transcript().outcome()); assertEquals(0,calls.get());
            String view=String.join("",run.awaitEvents(0,1));
            assertTrue(view.contains("Saved before crash")); assertTrue(view.contains("interrupted"));
            assertFalse(view.contains("PRIVATE_SENTINEL")); assertEquals(run.transcript(),store.transcript(id));
        }
    }
    @Test void failedEvidenceSaveDoesNotPublishTheUncommittedSpeech() throws Exception {
        TestFixtures.copyResources(root);
        var store=new RunStore(root.resolve("runs")) {
            @Override public void saveTranscript(Transcript transcript) {
                if (transcript.events().stream().anyMatch(event -> event.speaker()!=null)) throw new IllegalStateException("DISK_SECRET");
                super.saveTranscript(transcript);
            }
        };
        try (var app=new DebateApplication(root,store,model -> request -> RunLifecycleTest.completed("UNCOMMITTED_SENTINEL"))) {
            var run=app.start(TestFixtures.settings()); RunLifecycleTest.finish(run);
            assertEquals(Transcript.Outcome.ERROR,run.transcript().outcome());
            String view=String.join("",run.awaitEvents(0,1));
            assertFalse(view.contains("UNCOMMITTED_SENTINEL")); assertFalse(view.contains("DISK_SECRET"));
            assertFalse(Json.write(store.transcript(run.id())).contains("UNCOMMITTED_SENTINEL"));
        }
    }
    @Test void fractionalRoundsUnknownModelsAndPrivateImportFieldsAreRejected() throws Exception {
        var snapshot=TestFixtures.copyResources(root);
        Map<String,Object> body=new HashMap<>(TestFixtures.settings()); body.put("rounds",1.5);
        assertThrows(IllegalArgumentException.class,() -> RunSpec.resolve(body,snapshot.config()));
        body.put("rounds",1); body.put("agentModelPreset","missing");
        assertThrows(IllegalArgumentException.class,() -> RunSpec.resolve(body,snapshot.config()));
        assertThrows(IllegalArgumentException.class,() -> Json.parse("{\"key\":1,\"key\":2}"));
        assertThrows(IllegalArgumentException.class,() -> Json.parse("{} trailing"));
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"schemaVersion\":2,\"privateAssignments\":{}}",Transcript.class));
    }
}
