package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.evaluation.local.*;
import engine.transcript.*;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalEvaluationTest {
    @TempDir Path root;
    static Transcript evidence() {
        var member=new Participant("labour","PRIVATE_IDENTITY_SENTINEL",Party.LABOUR,"Labour");
        var topic=new Topic("topic-1","Housing","Build more public housing");
        return new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(member),List.of(topic),List.of(
                new PublicEvent("turn-1","topic-1",PublicEvent.Type.TOPIC,null,"TOPIC_SENTINEL"),
                new PublicEvent("turn-2","topic-1",PublicEvent.Type.CHAIR_RULING,null,"CHAIR_SENTINEL"),
                new PublicEvent("turn-3","topic-1",PublicEvent.Type.SPEECH,member,"Excellent policy 😀."),
                new PublicEvent("turn-4","topic-1",PublicEvent.Type.INTERJECTION,member,"A terrible plan.")),Transcript.Outcome.COMPLETE);
    }
    public static NlpResponse response(NlpRequest request) {
        var provenance=new NlpResponse.Provenance("vaderSentiment","3.3.2","0.1.0","a".repeat(64),"cpu",Map.of("vaderSentiment","3.3.2"),"Lexical proportions, not probabilities","punctuation-v1",Map.of());
        var items=request.turns().stream().map(turn -> new NlpResponse.Item(turn.turnId(),null,"ok",null,List.of(
                new NlpResponse.Chunk(0,0,turn.text().codePointCount(0,turn.text().length()),turn.text(),"positive",Map.of("positive",.9,"neutral",.1,"negative",0.),.8,null)),0)).toList();
        return new NlpResponse(2,List.of(new NlpResponse.Method(request.methods().get(0),"ok",null,provenance,items,0)));
    }
    static BackgroundJob finish(JobService service,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!service.find(id).terminal() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(service.find(id).terminal()); return service.find(id);
    }
    @Test void actualWireInputExcludesIdentityAndChairAndPreservesUnicodeEvidence() throws Exception {
        TestFixtures.copyResources(root); Transcript transcript=evidence(); List<String> wire=new CopyOnWriteArrayList<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/analyze",exchange -> {
            try (exchange) {
                String json=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); wire.add(json);
                byte[] output=Json.write(response(Json.read(json,NlpRequest.class))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,output.length); exchange.getResponseBody().write(output);
            }
        }); server.start();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var evaluator=new LocalEvaluationService(root,jobs,config -> new HttpNlpClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/analyze"),Duration.ofSeconds(5)));
            var job=finish(jobs,evaluator.start(transcript,Map.of()).id());
            assertEquals(BackgroundJob.State.COMPLETE,job.state());
            var report=(LocalReport)job.result(); assertEquals("ok",report.methods().get(0).status()); assertEquals(Hashes.sha256(Json.write(transcript)),report.transcriptSha256());
            assertEquals(2,report.methods().get(0).batches().get(0).items().size());
            for (String forbidden:List.of("PRIVATE_IDENTITY_SENTINEL","CHAIR_SENTINEL","TOPIC_SENTINEL","strategy","party","sourceContents","systemInstructions")) assertFalse(wire.get(0).contains(forbidden));
            assertTrue(wire.get(0).contains("turn-3")); assertTrue(wire.get(0).contains("😀"));
            assertFalse(wire.get(0).contains("Build more public housing"));
            var targets=NlpInputMapper.batches(transcript,"deberta-stance",evaluator.configuration());
            assertEquals("topic-1",targets.get(0).turns().get(0).targets().get(0).id());
            assertEquals("Build more public housing",targets.get(0).turns().get(0).targets().get(0).proposition());
            try (var restarted=new JobService(root.resolve("runs/jobs"))) {
                assertEquals(Json.parse(Json.write(job.result())),restarted.find(job.id()).result());
                assertEquals(BackgroundJob.State.COMPLETE,restarted.find(job.id()).state());
            }
        } finally { server.stop(0); }
    }
    @Test void malformedLaterBatchDoesNotDiscardEarlierEvidenceOrExposeExceptionText() throws Exception {
        TestFixtures.copyResources(root);
        Files.writeString(root.resolve("config/local-evaluation.json"),"{\"schemaVersion\":1,\"endpoint\":\"http://127.0.0.1:8765/v1/analyze\",\"timeoutSeconds\":5,\"batchSize\":1,\"methods\":[\"vader-sentiment\"]}");
        var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var evaluator=new LocalEvaluationService(root,jobs,config -> request -> {
                if (calls.incrementAndGet()==1) return response(request);
                return response(new NlpRequest(2,request.methods(),List.of(new NlpRequest.Turn(request.turns().get(0).turnId(),"MODIFIED_SECRET_SENTINEL",List.of()))));
            });
            var job=finish(jobs,evaluator.start(evidence(),Map.of()).id());
            assertEquals(BackgroundJob.State.FAILED,job.state());
            var batches=((LocalReport)job.result()).methods().get(0).batches();
            assertEquals("ok",batches.get(0).status()); assertEquals("failed",batches.get(1).status());
            assertFalse(Json.write(job).contains("SECRET_SENTINEL"));
        }
    }
    @Test void cancellationDiscardsUnfinishedBatchAndRetainsPreviousReport() throws Exception {
        TestFixtures.copyResources(root); var configFile=root.resolve("config/local-evaluation.json");
        Files.writeString(configFile,Files.readString(configFile).replace("\"batchSize\":50","\"batchSize\":1"));
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicInteger calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var evaluator=new LocalEvaluationService(root,jobs,config -> request -> {
                if (calls.incrementAndGet()==2) { entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                return response(request);
            });
            var job=evaluator.start(evidence(),Map.of()); assertTrue(entered.await(5,TimeUnit.SECONDS));
            jobs.cancel(job.id()); release.countDown();
            assertEquals(BackgroundJob.State.CANCELLED,jobs.find(job.id()).state());
            assertEquals(1,((LocalReport)jobs.find(job.id()).result()).methods().get(0).batches().size());
            assertFalse(Json.write(jobs.find(job.id())).contains("A terrible plan"));
        }
    }
    @Test void restartMarksPendingJobsInterruptedAndInputCannotSelectRemoteService() throws Exception {
        TestFixtures.copyResources(root); String id=UUID.randomUUID().toString(); var directory=root.resolve("runs/jobs").resolve(id);
        AtomicFiles.write(directory.resolve("job.json"),Json.write(new BackgroundJob(id,"local-evaluation",null,1L,null,BackgroundJob.State.RUNNING,"Analyzing",Map.of("prior","saved"))));
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            assertEquals(BackgroundJob.State.INTERRUPTED,jobs.find(id).state()); assertEquals(Map.of("prior","saved"),jobs.find(id).result());
            var evaluator=new LocalEvaluationService(root,jobs,config -> { fail("Must not construct a client"); return null; });
            assertThrows(IllegalArgumentException.class,() -> evaluator.start(evidence(),Map.of("methods",List.of("unknown"))));
            var source=evidence(); var empty=new Transcript(2,source.runId(),1L,2L,source.roster(),source.topics(),List.of(),Transcript.Outcome.COMPLETE);
            var job=finish(jobs,evaluator.start(empty,Map.of()).id());
            assertEquals("insufficient_evidence",((LocalReport)job.result()).methods().get(0).status());
        }
        assertThrows(IllegalArgumentException.class,() -> new LocalEvaluationConfig(1,URI.create("https://example.org/v1/analyze"),5,1,List.of("vader-sentiment")));
    }
}
