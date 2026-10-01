package engine.application;

import engine.TestFixtures;
import engine.evaluation.local.*;
import engine.utils.*;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class PilotTest {
    @TempDir Path root;
    /** Isolated browser QA server with synthetic labels and fake inference only. */
    public static void main(String[] args) throws Exception {
        Path workspace=Path.of("target/pilot-browser-fixture-"+UUID.randomUUID()); TestFixtures.copyResources(workspace);
        Files.writeString(workspace.resolve("synthetic.json"),Json.write(fixture("synthetic_fixture",200)));
        var app=new DebateApplication(workspace,new RunStore(workspace.resolve("runs")),model -> new engine.demo.DemoChatManager(),config -> LocalEvaluationTest::response);
        WebServer.start(18088,app); System.out.println("Synthetic pilot browser fixture: "+workspace.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
    }
    public static PilotDataset fixture(String type,int count) {
        var source=new PilotDataset.Source("Synthetic software test fixture","10.1234/TEST-FIXTURE","https://example.invalid/fixture","0".repeat(64));
        List<PilotDataset.Row> rows=new ArrayList<>(); boolean reviewed=!type.equals("unreviewed");
        for (int i=0;i<count;i++) rows.add(new PilotDataset.Row("pilot-"+i,"debate-"+(i/10),"SOURCE_SENTINEL-"+i,Hashes.sha256("speech-"+i),
                "Excellent policy for candidate "+i+".",new NlpRequest.Target("housing","Build public housing"),
                reviewed ? i<50 ? "calibration" : "held_out" : "",reviewed ? "positive" : "",reviewed ? "support" : "",reviewed ? "REVIEWER_SENTINEL" : ""));
        return new PilotDataset(1,type,source,rows);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> body(PilotDataset dataset) { return (Map<String,Object>)Json.parse(Json.write(dataset)); }
    private BackgroundJob finish(DebateApplication app,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (!app.background().find(id).terminal() && System.nanoTime()<until) Thread.sleep(10);
        assertTrue(app.background().find(id).terminal()); return app.background().find(id);
    }
    @Test void preparationIsReproducibleExcludesGroundingAndNeverCreatesGoldLabels() throws Exception {
        var snapshot=TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Pilot preparation cannot use debate providers"); return null; })) {
            var candidate=fixture("unreviewed",240); var rows=new ArrayList<>(candidate.rows());
            var actual=snapshot.excerpts().select(engine.agent.Party.LABOUR,1).get(0);
            var old=rows.get(0); String fileSha=(String)((Map<?,?>)snapshot.excerpts().provenance().get("source")).get("sha256");
            rows.set(0,new PilotDataset.Row(old.id(),old.sourceDebateId(),fileSha+":"+actual.get("sourceRow"),(String)actual.get("fullTextSha256"),old.text(),old.target(),"","","",""));
            String id=(String)app.pilots().importDataset(body(new PilotDataset(1,"unreviewed",candidate.source(),rows))).get("id");
            String first=(String)app.pilots().prepare(id,Map.of("seed",123)).get("id"), second=(String)app.pilots().prepare(id,Map.of("seed",123)).get("id");
            assertEquals(app.pilots().read(first),app.pilots().read(second)); var prepared=app.pilots().read(first); assertEquals(200,prepared.rows().size());
            assertEquals(123,prepared.preparation().seed()); assertTrue(prepared.preparation().groundingExclusionSha256().matches("[0-9a-f]{64}"));
            assertTrue(prepared.rows().stream().noneMatch(row -> row.id().equals("pilot-0")));
            assertTrue(prepared.rows().stream().allMatch(row -> row.reviewer().isEmpty() && row.sentiment().isEmpty() && row.stance().isEmpty()));
            var calibration=prepared.rows().stream().filter(row -> row.split().equals("calibration")).map(PilotDataset.Row::sourceDebateId).toList();
            var held=prepared.rows().stream().filter(row -> row.split().equals("held_out")).map(PilotDataset.Row::sourceDebateId).toList();
            assertFalse(calibration.isEmpty()); assertFalse(held.isEmpty()); assertTrue(Collections.disjoint(calibration,held));
            assertThrows(IllegalArgumentException.class,() -> app.pilots().evaluate(first,Map.of()));
        }
    }
    @SuppressWarnings("unchecked") @Test void invalidReviewSplitSourceDuplicatesAndGroundingFailBeforeInference() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var calls=new AtomicInteger();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> request -> { calls.incrementAndGet(); return LocalEvaluationTest.response(request); })) {
            String valid=Json.write(fixture("synthetic_fixture",200));
            List<java.util.function.Consumer<Map<String,Object>>> mutations=List.of(
                    data -> ((Map<String,Object>)((List<?>)data.get("rows")).get(0)).put("reviewer",""),
                    data -> ((Map<String,Object>)((List<?>)data.get("rows")).get(50)).put("sourceDebateId","debate-0"),
                    data -> ((Map<String,Object>)((List<?>)data.get("rows")).get(1)).put("id","pilot-0"),
                    data -> ((Map<String,Object>)((List<?>)data.get("rows")).get(1)).put("text","  Excellent   policy for candidate 0. "),
                    data -> data.put("apiKey","SECRET_SENTINEL"));
            for (var mutation:mutations) { var data=(Map<String,Object>)Json.parse(valid); mutation.accept(data); assertThrows(IllegalArgumentException.class,() -> app.pilots().importDataset(data)); }
            var data=(Map<String,Object>)Json.parse(valid); var row=(Map<String,Object>)((List<?>)data.get("rows")).get(0);
            row.put("sourceSpeechSha256",snapshot.excerpts().select(engine.agent.Party.LABOUR,1).get(0).get("fullTextSha256"));
            assertThrows(IllegalArgumentException.class,() -> app.pilots().importDataset(data)); assertEquals(0,calls.get()); assertTrue(app.pilots().list().isEmpty());
        }
    }
    @Test void reportsKeepSuccessfulMethodsAndNeverSendGoldReviewerOrSourceToClassifier() throws Exception {
        TestFixtures.copyResources(root); List<String> requests=new CopyOnWriteArrayList<>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/analyze",exchange -> {
            try (exchange) {
                String input=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); requests.add(input);
                var request=Json.read(input,NlpRequest.class);
                if (request.methods().get(0).equals("deberta-stance")) {
                    assertEquals(List.of(new NlpRequest.Target("housing","Build public housing")),request.turns().get(0).targets());
                } else assertTrue(request.turns().get(0).targets().isEmpty());
                var result=request.methods().get(0).equals("vader-sentiment") ? LocalEvaluationTest.response(request)
                        : new NlpResponse(2,List.of(new NlpResponse.Method(request.methods().get(0),"failed","model_unavailable",null,List.of(),0)));
                byte[] bytes=Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            }
        }); server.start();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> new HttpNlpClient(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/analyze"),java.time.Duration.ofSeconds(5)))) {
            String id=(String)app.pilots().importDataset(body(fixture("synthetic_fixture",200))).get("id");
            var job=finish(app,app.pilots().evaluate(id,Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance"))).id());
            assertEquals(BackgroundJob.State.FAILED,job.state()); String report=Json.write(job.result());
            assertTrue(report.contains("Synthetic fixture verifies software only")); assertTrue(report.contains("model_unavailable"));
            var methods=(List<?>)((Map<?,?>)job.result()).get("methods"); assertEquals(6,methods.size());
            assertEquals(150,((Map<?,?>)((Map<?,?>)methods.get(1)).get("metrics")).get("count"));
            assertEquals(150L,((Map<?,?>)((Map<?,?>)methods.get(3)).get("metrics")).get("failures"));
            assertEquals(600,requests.size());
            for (String request:requests) for (String forbidden:List.of("REVIEWER_SENTINEL","SOURCE_SENTINEL","sourceSpeech","\"reviewer\"","\"sentiment\"","\"stance\"","\"split\"")) assertFalse(request.contains(forbidden));
        } finally { server.stop(0); }
    }
    @Test void metricsCountFailuresAndAbstentionsWithoutRemovingGoldCases() {
        var values=List.of(new PilotMetrics.Observation("a","held_out","positive","positive",null,1,null),
                new PilotMetrics.Observation("b","held_out","negative","uncertain",null,2,null),
                new PilotMetrics.Observation("c","held_out","neutral","failed","missing",3,null));
        var metrics=PilotMetrics.calculate(values,List.of("negative","neutral","positive"));
        assertEquals(1d/3,(Double)metrics.get("macroF1"),.00001); assertEquals(1d/3,(Double)metrics.get("coverage"),.00001);
        assertEquals(1L,metrics.get("failures")); assertEquals(1L,metrics.get("abstentions")); assertEquals(3d,metrics.get("p95LatencyMillis"));
    }
    @Test void multipleChunksAreExplicitUnitFailuresRatherThanUnvalidatedAggregatedPredictions() throws Exception {
        TestFixtures.copyResources(root); var calls=new AtomicInteger();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> request -> {
            var response=LocalEvaluationTest.response(request); if (calls.incrementAndGet()!=1) return response;
            var method=response.methods().get(0); var item=method.items().get(0); String text=request.turns().get(0).text(); int space=text.indexOf(' ');
            var chunks=List.of(new NlpResponse.Chunk(0,0,space,text.substring(0,space),"positive",Map.of("positive",.9,"neutral",.1,"negative",0d),.8,null),
                    new NlpResponse.Chunk(0,space+1,text.length(),text.substring(space+1),"positive",Map.of("positive",.9,"neutral",.1,"negative",0d),.8,null));
            return new NlpResponse(2,List.of(new NlpResponse.Method(method.methodId(),"ok",null,method.provenance(),List.of(new NlpResponse.Item(item.turnId(),null,"ok",null,chunks,0)),0)));
        })) {
            String id=(String)app.pilots().importDataset(body(fixture("synthetic_fixture",200))).get("id"); var job=finish(app,app.pilots().evaluate(id,Map.of()).id());
            assertEquals(BackgroundJob.State.FAILED,job.state()); assertTrue(Json.write(job.result()).contains("pilot_unit_segmented"));
        }
    }
    @Test void cancelRetainsCommittedItemsAndRestartDoesNotResumeClassifierCalls() throws Exception {
        TestFixtures.copyResources(root); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger(); String jobId;
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> request -> {
            if (calls.incrementAndGet()==2) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); } }
            return LocalEvaluationTest.response(request);
        })) {
            String id=(String)app.pilots().importDataset(body(fixture("synthetic_fixture",200))).get("id"); jobId=app.pilots().evaluate(id,Map.of()).id();
            assertTrue(entered.await(10,TimeUnit.SECONDS)); app.background().cancel(jobId); release.countDown();
            var job=app.background().find(jobId); assertEquals(BackgroundJob.State.CANCELLED,job.state()); assertNotNull(job.result());
            assertTrue(Json.write(job.result()).contains("\"count\":1"));
        }
        var path=root.resolve("runs/jobs").resolve(jobId).resolve("job.json"); var prior=Json.read(Files.readString(path),BackgroundJob.class);
        AtomicFiles.write(path,Json.write(new BackgroundJob(prior.id(),prior.kind(),null,prior.createdAt(),null,BackgroundJob.State.RUNNING,"Simulated crash",prior.result())));
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Restart cannot generate"); return null; },config -> request -> { fail("Restart cannot analyze"); return null; })) {
            assertEquals(BackgroundJob.State.INTERRUPTED,app.background().find(jobId).state()); assertNotNull(app.background().find(jobId).result());
            assertEquals(1,app.pilots().list().size());
        }
    }
}
