package engine.application;

import engine.TestFixtures;
import engine.evaluation.local.*;
import engine.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TransformerDecisionTest {
    @TempDir Path root;
    static NlpResponse response(NlpRequest request,String settings,Map<String,Double> scores,boolean abstained) {
        String method=request.methods().get(0); boolean stance=method.equals("deberta-stance");
        var config=(Map<?,?>)Json.parse(settings); var spec=(Map<?,?>)config.get(stance ? "deberta" : "cardiff");
        var provenance=new NlpResponse.Provenance((String)spec.get("modelId"),(String)spec.get("revision"),NlpResponse.IMPLEMENTATION_VERSION,
                Hashes.sha256(settings),"cpu",Map.of(),"Synthetic controlled scores; no research accuracy","punctuation-v1",
                Map.of("minScore",config.get("minScore").toString(),"minMargin",config.get("minMargin").toString()));
        var ordered=scores.values().stream().sorted(Comparator.reverseOrder()).toList();
        double entropy=0; for (double value:ordered) if (value>0) entropy-=value*Math.log(value)/Math.log(scores.size());
        var uncertainty=new NlpResponse.Uncertainty(ordered.get(0),ordered.get(0)-ordered.get(1),entropy,abstained);
        String label=abstained ? "uncertain" : scores.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        var items=request.turns().stream().map(turn -> new NlpResponse.Item(turn.turnId(),stance ? turn.targets().get(0).id() : null,"ok",null,
                List.of(new NlpResponse.Chunk(0,0,turn.text().codePointCount(0,turn.text().length()),turn.text(),label,scores,null,uncertainty)),0)).toList();
        return new NlpResponse(2,List.of(new NlpResponse.Method(method,"ok",null,provenance,items,0)));
    }
    private NlpRequest request(String method) {
        return new NlpRequest(2,List.of(method),List.of(new NlpRequest.Turn("turn-1","Synthetic public evidence.",
                method.equals("deberta-stance") ? List.of(new NlpRequest.Target("housing","Build more homes")) : List.of())));
    }
    @Test void falseAbstentionAndFalseConfidentLabelsAreRejectedForBothMethods() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json"));
        for (String method:List.of("cardiff-sentiment","deberta-stance")) {
            var request=request(method); var labels=method.equals("deberta-stance") ? List.of("support","oppose","unrelated") : List.of("positive","negative","neutral");
            for (var response:List.of(response(request,settings,Map.of(labels.get(0),.9,labels.get(1),.1,labels.get(2),0d),true),
                    response(request,settings,Map.of(labels.get(0),.34,labels.get(1),.33,labels.get(2),.33),false),
                    response(request,settings,Map.of(labels.get(0),.55,labels.get(1),.225,labels.get(2),.225),false))) {
                NlpResponseValidator.validate(request,response);
                assertThrows(IllegalStateException.class,() -> LocalEvaluationService.validateProvenance(response.methods().get(0),method,settings));
            }
        }
    }
    @Test void equalityAndTiesFollowTheConfiguredRule() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json")).replace("0.6","0.5").replace("0.15","0.25");
        var request=request("cardiff-sentiment");
        var edge=response(request,settings,Map.of("positive",.5,"negative",.25,"neutral",.25),false).methods().get(0);
        assertDoesNotThrow(() -> LocalEvaluationService.validateProvenance(edge,"cardiff-sentiment",settings));
        var tied=response(request,settings,Map.of("positive",.5,"negative",.5,"neutral",0d),true).methods().get(0);
        assertDoesNotThrow(() -> LocalEvaluationService.validateProvenance(tied,"cardiff-sentiment",settings));
        var lowMargin=response(request,settings,Map.of("positive",.6,"negative",.4,"neutral",0d),false).methods().get(0);
        assertThrows(IllegalStateException.class,() -> LocalEvaluationService.validateProvenance(lowMargin,"cardiff-sentiment",settings));
    }
    @Test void reportedThresholdParametersCannotContradictCapturedValues() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json")).replace("0.6","0.5").replace("0.15","0.25");
        var edge=response(request("cardiff-sentiment"),settings,Map.of("positive",.5,"negative",.25,"neutral",.25),false).methods().get(0);
        var p=edge.provenance();
        for (String reported:List.of("0","NaN","1.1","PRIVATE_THRESHOLD_SENTINEL")) {
            var provenance=new NlpResponse.Provenance(p.modelId(),p.revision(),p.implementationVersion(),p.configSha256(),p.device(),p.libraries(),p.scoreSemantics(),p.segmentation(),Map.of("minScore",reported,"minMargin","0.25"));
            var forged=new NlpResponse.Method(edge.methodId(),edge.status(),edge.error(),provenance,edge.items(),edge.latencyMillis());
            var error=assertThrows(IllegalStateException.class,() -> LocalEvaluationService.validateProvenance(forged,"cardiff-sentiment",settings));
            assertFalse(error.getMessage().contains(reported));
        }
    }
    @Test void activeThresholdsStayFrozenAndInvalidLaterBatchRetainsOtherMethod() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json"));
        Files.writeString(root.resolve("config/local-evaluation.json"),Files.readString(root.resolve("config/local-evaluation.json")).replace("\"batchSize\":50","\"batchSize\":1"));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var service=new LocalEvaluationService(root,jobs,(config,captured) -> request -> {
                assertEquals(settings,captured);
                if (request.methods().get(0).equals("vader-sentiment")) return LocalEvaluationTest.response(request);
                boolean wrong=calls.incrementAndGet()==2;
                if (!wrong) { entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                return response(request,captured,Map.of("positive",.9,"negative",.1,"neutral",0d),wrong);
            });
            var job=service.start(LocalEvaluationTest.evidence(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment")));
            assertTrue(entered.await(5,TimeUnit.SECONDS)); Files.writeString(root.resolve("nlp/config/models.json"),settings.replace("0.6","0.95")); release.countDown();
            job=LocalEvaluationTest.finish(jobs,job.id()); assertEquals(BackgroundJob.State.FAILED,job.state());
            var methods=((LocalReport)job.result()).methods(); assertEquals("ok",methods.get(0).status());
            assertEquals("ok",methods.get(1).batches().get(0).status()); assertEquals("failed",methods.get(1).batches().get(1).status());
            assertNull(methods.get(1).batches().get(1).provenance());
        } finally { release.countDown(); }
    }
    @Test void pilotFailureRemainsInMetricsRatherThanScoringInvalidConfidentPrediction() throws Exception {
        TestFixtures.copyResources(root);
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var evaluation=new LocalEvaluationService(root,jobs,config -> null);
            var pilots=new PilotService(root,jobs,evaluation,(config,settings) -> request -> response(request,settings,Map.of("positive",.34,"negative",.33,"neutral",.33),false));
            String id=(String)pilots.importDataset(PilotTest.body(PilotTest.fixture("synthetic_fixture",200))).get("id");
            var job=LocalEvaluationTest.finish(jobs,pilots.evaluate(id,Map.of("methods",List.of("cardiff-sentiment"))).id());
            assertEquals(BackgroundJob.State.FAILED,job.state());
            var methods=(List<?>)((Map<?,?>)job.result()).get("methods");
            var calibration=(Map<?,?>)((Map<?,?>)methods.get(0)).get("metrics");
            assertEquals(50L,calibration.get("failures")); assertEquals(0d,calibration.get("coverage"));
        }
    }
}
