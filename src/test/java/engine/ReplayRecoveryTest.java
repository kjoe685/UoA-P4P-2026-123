package engine;

import engine.application.*;
import engine.agent.Party;
import engine.transcript.*;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class ReplayRecoveryTest {
    @TempDir Path root;
    private record Saved(RunStore store,Transcript transcript,List<String> view,byte[] publicBytes,byte[] setupBytes) { }

    private Saved saved() throws Exception {
        var snapshot=TestFixtures.copyResources(root);
        var spec=RunSpec.resolve(TestFixtures.settings(),snapshot.config());
        var setup=new PrivateSetup(spec,snapshot.config(),snapshot.sourceContents(),snapshot.sourceHashes(),Map.of("labour","PRIVATE_PROMPT_SENTINEL"));
        var labour=new Participant("labour","Labour MP",Party.LABOUR,"Labour");
        var national=new Participant("national","National MP",Party.NATIONAL,"National");
        String id=UUID.randomUUID().toString();
        var transcript=new Transcript(2,id,0,900L,List.of(labour,national),spec.topics(),List.of(
                new PublicEvent("turn-1","topic-1",PublicEvent.Type.TOPIC,null,"Housing"),
                new PublicEvent("turn-2","topic-1",PublicEvent.Type.CHAIR,null,"Address the House."),
                new PublicEvent("turn-3","topic-1",PublicEvent.Type.SPEECH,labour,"Committed Māori evidence ā 🙂."),
                new PublicEvent("turn-4","topic-1",PublicEvent.Type.CHAIR_RULING,null,"Return to the proposition."),
                new PublicEvent("turn-5","topic-1",PublicEvent.Type.INTERJECTION,national,"A committed challenge."),
                new PublicEvent("turn-6","topic-1",PublicEvent.Type.SPEECH,national,"A committed reply.")),Transcript.Outcome.COMPLETE);
        var store=new RunStore(root.resolve("runs")); RunSession.imported(transcript,setup,store);
        return new Saved(store,transcript,store.view(id),Files.readAllBytes(runFile(id,"transcript.json")),Files.readAllBytes(runFile(id,"setup.json")));
    }
    private Path runFile(String id,String name) { return root.resolve("runs").resolve(id).resolve(name); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String text) { return (Map<String,Object>)Json.parse(text); }
    private static String calling(Participant member) {
        return Json.write(Map.of("type","calling","memberId",member.id(),"name",member.name(),"party",member.party().name(),"partyName",member.partyName(),"interjection",false));
    }
    private static List<String> corrupt(Saved saved,String kind) {
        var changed=new ArrayList<>(saved.view());
        switch (kind) {
            case "sitting" -> { var event=object(changed.get(0)); event.put("id",UUID.randomUUID().toString()); event.put("members",List.of(Map.of("name","STALE_SITTING_SENTINEL"))); changed.set(0,Json.write(event)); }
            case "speech" -> { var event=object(changed.get(3)); event.put("text","STALE_SPEECH_SENTINEL"); event.put("name","STALE_SPEAKER_SENTINEL"); changed.set(3,Json.write(event)); }
            case "terminal" -> { var event=object(changed.get(changed.size()-1)); event.put("outcome","error"); event.put("at",123); changed.set(changed.size()-1,Json.write(event)); }
            case "extra" -> { var event=object(changed.get(3)); event.put("turnId","uncommitted-turn"); event.put("text","UNCOMMITTED_CACHE_SENTINEL"); changed.add(changed.size()-1,Json.write(event)); }
            case "order" -> Collections.swap(changed,3,6);
            case "missing-middle" -> changed.remove(3);
            case "calling" -> changed.add(3,Json.write(Map.of("type","calling","memberId","absent","name","STALE_CALLING_SENTINEL")));
            default -> throw new AssertionError(kind);
        }
        return changed;
    }
    private DebateApplication reopen(RunStore store) {
        return new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not construct providers or resume inference"); });
    }
    private void evidenceUnchanged(Saved saved) throws Exception {
        String id=saved.transcript().runId();
        assertArrayEquals(saved.publicBytes(),Files.readAllBytes(runFile(id,"transcript.json")));
        assertArrayEquals(saved.setupBytes(),Files.readAllBytes(runFile(id,"setup.json")));
    }
    @ParameterizedTest @ValueSource(strings={"sitting","speech","terminal","extra","order","missing-middle","calling"})
    void inconsistentCacheIsRebuiltAndSavedFromCommittedEvidence(String kind) throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); saved.store().saveView(id,corrupt(saved,kind));
        try (var app=reopen(saved.store())) {
            assertEquals(saved.transcript(),app.find(id).transcript());
            assertEquals(saved.view(),app.find(id).awaitEvents(0,1),kind);
            assertEquals(saved.view(),saved.store().view(id),"Replacement must be durable before replay");
            assertFalse(String.join("",app.find(id).awaitEvents(0,1)).contains("SENTINEL"));
        }
        evidenceUnchanged(saved);
        try (var app=reopen(saved.store())) { assertEquals(saved.view(),app.find(id).awaitEvents(0,1)); }
    }
    @Test void malformedCompleteCacheReplacementIsPersisted() throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); saved.store().saveView(id,List.of("{BROKEN"));
        try (var app=reopen(saved.store())) {
            assertEquals(saved.view(),app.find(id).awaitEvents(0,1));
            assertEquals(saved.view(),saved.store().view(id));
        }
        evidenceUnchanged(saved);
    }
    @Test void validOperationalEventsAndExactSseIndexesSurviveRecovery() throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); var cache=new ArrayList<>(saved.view());
        cache.set(0," \n"+cache.get(0)+" "); cache.add(3,calling(saved.transcript().roster().get(0)));
        saved.store().saveView(id,cache); byte[] original=Files.readAllBytes(runFile(id,"view.json"));
        try (var app=reopen(saved.store())) {
            assertEquals(cache,app.find(id).awaitEvents(0,1));
            assertEquals(cache.subList(4,cache.size()),app.find(id).awaitEvents(4,1));
        }
        assertArrayEquals(original,Files.readAllBytes(runFile(id,"view.json"))); evidenceUnchanged(saved);
    }
    @Test void committedTailAfterCrashIsAppendedWithoutChangingValidPrefixOrResuming() throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); var source=saved.transcript();
        saved.store().saveTranscript(new Transcript(2,id,source.startedAt(),null,source.roster(),source.topics(),source.events(),Transcript.Outcome.RUNNING));
        var prefix=new ArrayList<>(saved.view().subList(0,4)); prefix.add(3,calling(source.roster().get(0)));
        saved.store().saveView(id,prefix);
        try (var app=reopen(saved.store())) {
            var run=app.find(id); var recovered=run.awaitEvents(0,1);
            assertEquals(prefix,recovered.subList(0,prefix.size()));
            assertEquals(source.events(),run.transcript().events()); assertEquals(Transcript.Outcome.INTERRUPTED,run.transcript().outcome());
            assertEquals(recovered,saved.store().view(id));
            assertEquals(List.of("turn-1","turn-2","turn-3","turn-4","turn-5","turn-6"),recovered.stream().map(ReplayRecoveryTest::object)
                    .filter(event -> event.containsKey("turnId")).map(event -> event.get("turnId")).toList());
        }
        assertArrayEquals(saved.setupBytes(),Files.readAllBytes(runFile(id,"setup.json")));
    }
    @Test void refusedCacheReplacementDoesNotPublishAnUnsavedReplay() throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); saved.store().saveView(id,List.of("{BROKEN"));
        byte[] broken=Files.readAllBytes(runFile(id,"view.json"));
        var refusing=new RunStore(root.resolve("runs")) {
            @Override public void saveView(String runId,List<String> events) { throw new IllegalStateException("CACHE_DISK_SENTINEL"); }
        };
        assertThrows(IllegalStateException.class,() -> RunSession.restore(id,refusing));
        assertArrayEquals(broken,Files.readAllBytes(runFile(id,"view.json"))); evidenceUnchanged(saved);
    }
    @Test void mismatchedTranscriptStorageIdIsRefusedWithoutWritingEitherRun() throws Exception {
        var saved=saved(); var source=saved.transcript(); String id=source.runId(),otherId=UUID.randomUUID().toString();
        var copied=new Transcript(2,otherId,source.startedAt(),source.endedAt(),source.roster(),source.topics(),source.events(),source.outcome());
        Files.writeString(runFile(id,"transcript.json"),Json.write(copied));
        byte[] copiedBytes=Files.readAllBytes(runFile(id,"transcript.json")),viewBytes=Files.readAllBytes(runFile(id,"view.json"));
        assertThrows(IllegalArgumentException.class,() -> RunSession.restore(id,saved.store()));
        assertArrayEquals(copiedBytes,Files.readAllBytes(runFile(id,"transcript.json")));
        assertArrayEquals(viewBytes,Files.readAllBytes(runFile(id,"view.json")));
        assertArrayEquals(saved.setupBytes(),Files.readAllBytes(runFile(id,"setup.json")));
        assertFalse(Files.exists(root.resolve("runs").resolve(otherId)));
        try (var app=reopen(saved.store())) { assertTrue(app.runs().isEmpty()); }
    }
    @Test void browserCommandAndGuidedReplayAgreeWithPublicExportsAfterRecovery() throws Exception {
        var saved=saved(); String id=saved.transcript().runId(); saved.store().saveView(id,corrupt(saved,"speech"));
        try (var app=reopen(saved.store())) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient();
                String route=base+"/api/debates/"+id;
                var replay=http.send(HttpRequest.newBuilder(URI.create(route+"/events")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,replay.statusCode()); assertTrue(replay.body().contains("Committed Māori evidence ā 🙂.")); assertFalse(replay.body().contains("SENTINEL"));
                var json=http.send(HttpRequest.newBuilder(URI.create(route+"/transcript")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(saved.transcript(),Json.read(json.body(),Transcript.class));
                var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes,true,StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),output); cli.command(new String[]{"watch",id});
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Committed Māori evidence ā 🙂.")); assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
                bytes.reset(); new Main(base,new Scanner("3\n"+id+"\n0\n"),output).menu();
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Committed Māori evidence ā 🙂.")); assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
                Path exported=root.resolve("public.json"); cli.command(new String[]{"transcript",id,exported.toString()});
                assertArrayEquals(saved.publicBytes(),Files.readAllBytes(exported));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
        evidenceUnchanged(saved);
    }
}
