package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.demo.DemoChatManager;
import engine.transcript.*;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GroundingSelectionTest {
    @TempDir Path root;
    @Test void zeroAndOverridesFreezeOnlyChosenOwnExcerptsAndRejectImpossibleCountsBeforeProviders() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var calls=new AtomicInteger(); var store=new RunStore(root.resolve("runs"));
        try (var app=new DebateApplication(root,store,model -> { calls.incrementAndGet(); return new DemoChatManager(); })) {
            var settings=new LinkedHashMap<String,Object>(Map.of("topics",List.of("Housing"),"rounds",1,"agentModelPreset","demo","groundingCount",0,
                    "members",List.of(Map.of("party","LABOUR","groundingCount",1),Map.of("party","NATIONAL"))));
            var run=app.start(settings); var setup=store.setup(run.id());
            String first=snapshot.excerpts().getExcerpts(Party.LABOUR).get(0), second=snapshot.excerpts().getExcerpts(Party.LABOUR).get(1);
            assertTrue(setup.resolvedPrompts().get("labour").contains(first)); assertFalse(setup.resolvedPrompts().get("labour").contains(second));
            assertFalse(setup.resolvedPrompts().get("national").contains(snapshot.excerpts().getExcerpts(Party.NATIONAL).get(0)));
            String selected=setup.sourceContents().get("selection/hansard.json"); assertTrue(selected.contains("textSha256")); assertTrue(selected.contains("corpusSha256"));
            assertFalse(selected.contains(second)); assertFalse(Json.write(run.transcript()).contains("selection/hansard.json"));
            var persisted=app.settings().save("counts",settings,snapshot.config());
            assertEquals(0,persisted.get("groundingCount")); assertEquals(1,((Map<?,?>)((List<?>)persisted.get("members")).get(0)).get("groundingCount"));
            calls.set(0); settings.put("groundingCount",1000);
            assertThrows(IllegalArgumentException.class,() -> app.start(settings)); assertEquals(0,calls.get());
            for (Object invalid:List.of(-2,1.5,"1")) {
                settings.put("groundingCount",invalid); assertThrows(IllegalArgumentException.class,() -> app.start(settings));
            }
            app.assets().update("data/hansard/excerpts.json",app.assets().read("data/hansard/excerpts.json").replace(first,"EDITED_CORPUS_SENTINEL"),true);
            assertEquals(selected,store.setup(run.id()).sourceContents().get("selection/hansard.json"));
        }
    }
    @SuppressWarnings("unchecked") @Test void legacySavedSetupReplaysWithoutRewritingOrResuming() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs")); String id=UUID.randomUUID().toString();
        var spec=RunSpec.resolve(TestFixtures.settings(),snapshot.config());
        store.create(id,new PrivateSetup(spec,snapshot.config(),snapshot.sourceContents(),snapshot.sourceHashes(),Map.of()));
        var path=root.resolve("runs").resolve(id).resolve("setup.json"); var original=(Map<String,Object>)Json.parse(Files.readString(path));
        var settings=(Map<String,Object>)original.get("settings"); settings.remove("groundingCount");
        for (Object member:(List<?>)settings.get("members")) ((Map<?,?>)member).remove("groundingCount");
        AtomicFiles.write(path,Json.write(original)); String before=Hashes.sha256(Files.readAllBytes(path));
        var source=LlmEvaluationTest.evidence(); store.saveTranscript(new Transcript(2,id,1L,2L,source.roster(),source.topics(),source.events(),Transcript.Outcome.COMPLETE));
        store.saveView(id,List.of());
        try (var app=new DebateApplication(root,store,model -> { fail("Saved runs must not instantiate providers"); return null; })) {
            assertEquals(3,app.find(id).transcript().events().stream().filter(event -> event.speaker()!=null).count());
            assertEquals(-1,store.setup(id).settings().groundingCount()); assertNull(store.setup(id).settings().members().get(0).groundingCount());
            assertEquals(before,Hashes.sha256(Files.readAllBytes(path)));
        }
    }
}
