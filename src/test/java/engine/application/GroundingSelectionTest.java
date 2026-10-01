package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.demo.DemoChatManager;
import engine.chat.ChatRequest;
import engine.transcript.*;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class GroundingSelectionTest {
    @TempDir Path root;
    @Test void allReviewedExcerptsReachOnlyTheirOwnAgentWithoutHistoricalIdentitiesOrCitationMetadata() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs"));
        List<ChatRequest> requests=new CopyOnWriteArrayList<>();
        var records=new ArrayList<Map<String,Object>>();
        for (Party party:Party.values()) records.addAll(snapshot.excerpts().select(party,-1));
        assertEquals(500,records.size());
        var selection=(Map<?,?>)snapshot.excerpts().provenance().get("selection");
        assertEquals("verbatim-complete-generic-sentences-v2",selection.get("excerptPolicy"));
        try (var app=new DebateApplication(root,store,model -> request -> {
            requests.add(request); return RunLifecycleTest.completed("A public argument about housing.");
        })) {
            var members=Arrays.stream(Party.values()).map(party -> Map.of("party",party.name())).toList();
            var run=app.start(Map.of("topics",List.of("Housing"),"rounds",1,"agentModelPreset","demo",
                    "groundingCount",-1,"members",members));
            RunLifecycleTest.finish(run); var setup=store.setup(run.id());
            for (Party party:Party.values()) {
                String prompt=setup.resolvedPrompts().get(party.name().toLowerCase(Locale.ROOT));
                assertTrue(requests.stream().anyMatch(request -> request.systemInstructions().equals(prompt)),"No captured request for "+party);
                assertTrue(prompt.contains("not evidence of current party policy"));
                for (String text:snapshot.excerpts().getExcerpts(party)) {
                    assertTrue(prompt.contains(text));
                    assertTrue(Character.isUpperCase(text.codePointAt(0)),text);
                    assertTrue(text.matches("(?s).*[.!?][”\"')\\]]*$"),text);
                }
                for (Party other:Party.values()) if (other!=party)
                    for (String text:snapshot.excerpts().getExcerpts(other)) assertFalse(prompt.contains(text));
                for (var record:records) {
                    String name=(String)record.get("speaker");
                    assertFalse(Pattern.compile(Pattern.quote(name),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(prompt).find(),name);
                }
                for (String field:List.of("sourceRow","fullTextSha256","charStart","charEnd")) assertFalse(prompt.contains(field));
            }
            String privateSelection=setup.sourceContents().get("selection/hansard.json");
            assertTrue(privateSelection.contains("JACINDA ARDERN"));
            assertTrue(privateSelection.contains("sourceRow"));
            String publicJson=Json.write(run.transcript()), publicText=app.textExport(run.id());
            for (String field:List.of("fullTextSha256","sourceRow","selection/hansard.json","JACINDA ARDERN")) {
                assertFalse(publicJson.contains(field)); assertFalse(publicText.contains(field));
            }
        }
    }
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
            var updated=(Map<?,?>)Json.parse(app.assets().read("data/hansard/excerpts.json"));
            Collections.swap((List<?>)updated.get("Labour"),0,1);
            app.assets().update("data/hansard/excerpts.json",Json.write(updated),true);
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
