package engine.application;

import engine.TestFixtures;
import engine.evaluation.local.*;
import engine.utils.*;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class PilotContentSplitTest {
    @TempDir Path root;
    private static PilotDataset.Row withHash(PilotDataset.Row row,String hash) {
        return new PilotDataset.Row(row.id(),row.sourceDebateId(),row.sourceSpeechId(),hash,row.text(),row.target(),row.split(),row.sentiment(),row.stance(),row.reviewer());
    }
    @Test void reviewedContentOverlapUnderDifferentIdsIsRejectedBeforeImportOrInference() throws Exception {
        TestFixtures.copyResources(root); var dataset=PilotTest.fixture("synthetic_fixture",200); var body=PilotTest.body(dataset);
        @SuppressWarnings("unchecked") var row=(Map<String,Object>)((List<?>)body.get("rows")).get(50);
        row.put("sourceSpeechSha256",dataset.rows().get(0).sourceSpeechSha256());
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Import does not generate"); return null; },config -> { fail("Import does not analyze"); return null; })) {
            assertThrows(IllegalArgumentException.class,() -> app.pilots().importDataset(body)); assertTrue(app.pilots().list().isEmpty());
            var server=WebServer.start(0,app);
            try {
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/pilots"))
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(400,response.statusCode()); assertFalse(response.body().contains("SOURCE_SENTINEL")); assertFalse(response.body().contains(dataset.rows().get(0).sourceSpeechSha256()));
                assertTrue(app.pilots().list().isEmpty()); assertTrue(app.background().list().isEmpty()); assertFalse(Files.exists(root.resolve("runs/pilots")));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void preparationCannotPersistCrossSplitContentAndPreservesItsOriginalSource() throws Exception {
        TestFixtures.copyResources(root); var source=PilotTest.fixture("unreviewed",200);
        var duplicateRows=source.rows().stream().map(row -> withHash(row,Hashes.sha256("SYNTHETIC_SHARED_FULL_SPEECH"))).toList();
        var candidate=new PilotDataset(1,"unreviewed",source.source(),duplicateRows);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Preparation does not generate"); return null; },config -> { fail("Preparation does not analyze"); return null; })) {
            String id=(String)app.pilots().importDataset(PilotTest.body(candidate)).get("id"); Path file=root.resolve("runs/pilots/"+id+".json"); byte[] original=Files.readAllBytes(file);
            var error=assertThrows(IllegalArgumentException.class,() -> app.pilots().prepare(id,Map.of("seed",123)));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("SYNTHETIC_SHARED_FULL_SPEECH"));
            assertEquals(1,app.pilots().list().size()); assertArrayEquals(original,Files.readAllBytes(file)); assertEquals(candidate,app.pilots().read(id));
            assertTrue(app.background().list().isEmpty());
            try (var files=Files.list(file.getParent())) { assertEquals(List.of(file),files.toList()); }
        }
    }
    @Test void repeatedContentWithinOneSplitRemainsValidAndBothSplitsStayDisjoint() throws Exception {
        TestFixtures.copyResources(root); var source=PilotTest.fixture("synthetic_fixture",200); var rows=new ArrayList<>(source.rows());
        rows.set(10,withHash(rows.get(10),rows.get(0).sourceSpeechSha256()));
        rows.set(60,withHash(rows.get(60),rows.get(50).sourceSpeechSha256()));
        var dataset=new PilotDataset(1,"synthetic_fixture",source.source(),rows);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> { fail("Import must not analyze"); return null; })) {
            String id=(String)app.pilots().importDataset(PilotTest.body(dataset)).get("id"); assertEquals(dataset,app.pilots().read(id));
            var calibration=new HashSet<String>(); var held=new HashSet<String>();
            dataset.rows().forEach(row -> (row.split().equals("calibration") ? calibration : held).add(row.sourceSpeechSha256()));
            assertTrue(Collections.disjoint(calibration,held)); assertEquals(49,calibration.size()); assertEquals(149,held.size());
        }
    }
}
