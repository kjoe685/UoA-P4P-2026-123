package engine.application;

import com.sun.net.httpserver.HttpServer;
import engine.TestFixtures;
import engine.evaluation.local.*;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NlpProvenanceTest {
    @TempDir Path root;
    private static NlpResponse identified(NlpRequest request,String hash,String implementation) {
        var method=LocalEvaluationTest.response(request).methods().get(0);
        var old=method.provenance();
        var provenance=new NlpResponse.Provenance(old.modelId(),old.revision(),implementation,hash,
                old.device(),old.libraries(),old.scoreSemantics(),old.segmentation(),old.parameters());
        return new NlpResponse(2,List.of(new NlpResponse.Method(method.methodId(),method.status(),method.error(),provenance,method.items(),method.latencyMillis())));
    }
    @Test void staleLaterWireBatchRetainsEarlierCapturedEvidenceAfterRestart() throws Exception {
        TestFixtures.copyResources(root);
        String captured=Files.readString(root.resolve("nlp/config/models.json"));
        Files.writeString(root.resolve("config/local-evaluation.json"),Files.readString(root.resolve("config/local-evaluation.json")).replace("\"batchSize\":50","\"batchSize\":1"));
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/analyze",exchange -> {
            try (exchange) {
                var request=Json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),NlpRequest.class);
                String source=calls.incrementAndGet()==1 ? captured : captured.replace("0.6","0.9");
                byte[] body=Json.write(identified(request,Hashes.sha256(source),"0.3.0")).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            }
        }); server.start();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var service=new LocalEvaluationService(root,jobs,config -> new HttpNlpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/analyze"),Duration.ofSeconds(5)));
            var job=LocalEvaluationTest.finish(jobs,service.start(LocalEvaluationTest.evidence(),Map.of()).id());
            assertEquals(BackgroundJob.State.FAILED,job.state()); assertEquals(2,calls.get());
            var batches=((LocalReport)job.result()).methods().get(0).batches();
            assertEquals("ok",batches.get(0).status()); assertEquals(Hashes.sha256(captured),batches.get(0).provenance().configSha256());
            assertEquals("failed",batches.get(1).status()); assertNull(batches.get(1).provenance()); assertTrue(batches.get(1).items().isEmpty());
            assertEquals("transport_or_response_failed",batches.get(1).error());
            assertFalse(Json.write(job).contains(Hashes.sha256(captured.replace("0.6","0.9"))));
            try (var restarted=new JobService(root.resolve("runs/jobs"))) {
                assertEquals(Json.parse(Json.write(job.result())),restarted.find(job.id()).result());
            }
        } finally { server.stop(0); }
    }
    @Test void syntheticPilotMismatchFailsWithoutScoringStaleLabels() throws Exception {
        TestFixtures.copyResources(root); String captured=Files.readString(root.resolve("nlp/config/models.json"));
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var evaluation=new LocalEvaluationService(root,jobs,config -> null);
            var pilots=new PilotService(root,jobs,evaluation,(config,settings) -> request -> identified(request,Hashes.sha256(captured+"\n"),"0.3.0"));
            String id=(String)pilots.importDataset(PilotTest.body(PilotTest.fixture("synthetic_fixture",200))).get("id");
            var job=LocalEvaluationTest.finish(jobs,pilots.evaluate(id,Map.of()).id());
            assertEquals(BackgroundJob.State.FAILED,job.state());
            var methods=(List<?>)((Map<?,?>)job.result()).get("methods");
            assertEquals(50L,((Map<?,?>)((Map<?,?>)methods.get(0)).get("metrics")).get("failures"));
            assertEquals(150L,((Map<?,?>)((Map<?,?>)methods.get(1)).get("metrics")).get("failures"));
            assertFalse(Json.write(job.result()).contains(Hashes.sha256(captured+"\n")));
        }
    }
    @Test void exactSourceHashAndCurrentImplementationAreRequired() throws Exception {
        TestFixtures.copyResources(root);
        String captured=Files.readString(root.resolve("nlp/config/models.json")).replace("The speaker supports", "The Māori 😀 speaker supports")+"\n";
        var request=NlpInputMapper.batches(LocalEvaluationTest.evidence(),"vader-sentiment",new LocalEvaluationService(root,null,config -> null).configuration()).get(0);
        var valid=identified(request,Hashes.sha256(captured),"0.3.0").methods().get(0);
        assertDoesNotThrow(() -> LocalEvaluationService.validateProvenance(valid,"vader-sentiment",captured));
        assertThrows(IllegalStateException.class,() -> LocalEvaluationService.validateProvenance(identified(request,Hashes.sha256(captured),"0.2.0").methods().get(0),"vader-sentiment",captured));
        assertThrows(IllegalStateException.class,() -> LocalEvaluationService.validateProvenance(identified(request,Hashes.sha256(captured.strip()),"0.3.0").methods().get(0),"vader-sentiment",captured));
    }
}
