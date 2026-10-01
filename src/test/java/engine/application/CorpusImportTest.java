package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.demo.DemoChatManager;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorpusImportTest {
    @TempDir Path root;
    @SuppressWarnings("unchecked") private Map<String,Object> corpus(String text) { return (Map<String,Object>)Json.parse(text); }
    @SuppressWarnings("unchecked") private Map<String,Object> record(Map<String,Object> corpus,int index) { return (Map<String,Object>)((List<?>)corpus.get("Labour")).get(index); }
    @Test void bundledSourceHasFiveHundredGenuineRecordsAndImportDoesNotChangeFrozenPrivateSelections() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs"));
        try (var app=new DebateApplication(root,store,model -> new DemoChatManager())) {
            var status=app.corpus().status(); assertEquals(500,status.get("totalExcerpts"));
            for (Party party:Party.values()) assertEquals(100,app.configuration().excerpts().getExcerpts(party).size());
            var settings=new LinkedHashMap<>(TestFixtures.settings()); settings.put("groundingCount",2);
            var run=app.start(settings); var before=store.setup(run.id()).sourceContents().get("selection/hansard.json");
            assertTrue(before.contains("sourceRow")); assertTrue(before.contains("fullTextSha256")); assertTrue(before.contains("10.7910/DVN/L4OAKN"));
            var changed=corpus(app.assets().read(CorpusService.PATH)); Collections.swap((List<?>)changed.get("Labour"),0,1); String text=Json.write(changed);
            var preview=app.corpus().importCorpus(Map.of("text",text,"save",false));
            assertEquals(status.get("sha256"),app.corpus().status().get("sha256"));
            assertEquals(false,preview.get("saved"));
            var imported=app.corpus().importCorpus(Map.of("text",text,"save",true,"expectedSha256",preview.get("previousSha256")));
            assertEquals(true,imported.get("saved")); assertEquals(Hashes.sha256(text),app.corpus().status().get("sha256"));
            assertEquals(before,store.setup(run.id()).sourceContents().get("selection/hansard.json"));
            var next=app.start(settings); assertNotEquals(before,store.setup(next.id()).sourceContents().get("selection/hansard.json"));
            String publicJson=Json.write(run.transcript()); assertFalse(publicJson.contains("sourceRow")); assertFalse(publicJson.contains("fullTextSha256"));
            assertThrows(IllegalArgumentException.class,() -> app.corpus().importCorpus(Map.of("text",text,"save",true,"expectedSha256",preview.get("previousSha256"))));
        }
    }
    @SuppressWarnings("unchecked") @Test void malformedDuplicatesHashesSpansCountsAndSecretFieldsNeverOverwriteCorpus() throws Exception {
        TestFixtures.copyResources(root);
        var assets=new AssetService(root); var service=new CorpusService(assets); String original=assets.read(CorpusService.PATH), hash=Hashes.sha256(original);
        List<java.util.function.Consumer<Map<String,Object>>> mutations=List.of(
                value -> record(value,1).put("text",record(value,0).get("text")),
                value -> record(value,1).put("sourceRow",record(value,0).get("sourceRow")),
                value -> record(value,0).put("textSha256","0".repeat(64)),
                value -> record(value,0).put("charEnd",1),
                value -> record(value,0).put("date","2019-02-30"),
                value -> record(value,0).put("sourceParty","National"),
                value -> record(value,0).put("sourceRow",1.5),
                value -> record(value,0).put("credential","SECRET_SENTINEL"),
                value -> ((List<?>)value.get("ACT")).remove(0),
                value -> ((Map<String,Object>)((Map<?,?>)value.get("_corpus")).get("source")).put("url","https://name:secret@example.com/file"),
                value -> ((Map<String,Object>)((Map<?,?>)value.get("_corpus")).get("source")).put("apiKey","SECRET_SENTINEL"),
                value -> value.put("unknownParty",List.of()));
        for (var mutation:mutations) {
            var changed=corpus(original); mutation.accept(changed);
            assertThrows(IllegalArgumentException.class,() -> service.importCorpus(Map.of("text",Json.write(changed),"save",true,"expectedSha256",hash)));
            assertEquals(original,assets.read(CorpusService.PATH));
        }
        var legacy=corpus(original); legacy.remove("_corpus");
        assertThrows(IllegalArgumentException.class,() -> service.importCorpus(Map.of("text",Json.write(legacy),"save",false)));
        assertThrows(IllegalArgumentException.class,() -> service.importCorpus(Map.of("text",original,"save",true)));
        assertEquals(original,assets.read(CorpusService.PATH));
    }
}
