package engine.application;

import engine.*;
import engine.agent.Party;
import engine.chat.*;
import engine.config.*;
import engine.evaluation.*;
import engine.evaluation.llm.*;
import engine.transcript.*;
import engine.utils.*;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class LlmEvaluationTest {
    @TempDir Path root;
    static final ModelConfig MODEL=new ModelConfig("openai","gpt-4o-mini",.1,null,4096,5);
    public static Transcript evidence() {
        var a=new Participant("a","Synthetic Labour",Party.LABOUR,"Labour");
        var b=new Participant("b","Synthetic National",Party.NATIONAL,"National");
        return new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(a,b),List.of(new Topic("topic-1","Synthetic evaluator QA","Expand public housing"),new Topic("topic-2","Second topic",null)),List.of(
                new PublicEvent("turn-1","topic-1",PublicEvent.Type.CHAIR_RULING,null,"Order!"),
                new PublicEvent("turn-2","topic-1",PublicEvent.Type.SPEECH,a,"Investment increases supply because new homes reduce the shortage."),
                new PublicEvent("turn-10","topic-1",PublicEvent.Type.SPEECH,b,"How will the proposal be funded without cutting other services?"),
                new PublicEvent("turn-11","topic-1",PublicEvent.Type.SPEECH,a,"The funding concern is fair. I would phase investment and review costs before expansion.")),Transcript.Outcome.COMPLETE);
    }
    public static LlmAssessment answer(String topic,List<PublicEvent> events,List<Participant> roster,LlmRubric rubric) {
        return new LlmAssessment(topic,roster.stream().map(person -> {
            var own=events.stream().filter(e -> e.speaker()!=null && e.speaker().id().equals(person.id())).toList();
            return new LlmAssessment.ParticipantAssessment(person.id(),rubric.metrics().stream().map(metric -> {
                List<String> refs=new ArrayList<>(own.stream().map(PublicEvent::id).toList());
                var prior=events.stream().filter(e -> e.speaker()!=null && !e.speaker().id().equals(person.id()) && !own.isEmpty() && events.indexOf(e)<events.indexOf(own.get(own.size()-1))).findFirst();
                boolean supported=own.size()>=metric.minimumParticipantTurns() && (!metric.requiresPriorOtherSpeaker() || prior.isPresent());
                if (metric.requiresPriorOtherSpeaker() && prior.isPresent()) refs.add(prior.get().id());
                return new LlmAssessment.MetricAssessment(metric.id(),supported ? EvaluationStatus.OK : EvaluationStatus.INSUFFICIENT_EVIDENCE,
                        supported ? metric.minimum() : null,"Synthetic software fixture; this is not a research judgment.",refs);
            }).toList());
        }).toList());
    }
    @SuppressWarnings("unchecked") public static ChatResponse fake(ChatRequest request,LlmRubric rubric) {
        var body=(Map<String,Object>)Json.parse(request.messages().get(0).content());
        var topic=Json.read(Json.write(body.get("topic")),Topic.class);
        var roster=((List<Object>)body.get("participants")).stream().map(item -> Json.read(Json.write(item),Participant.class)).toList();
        var events=((List<Object>)body.get("events")).stream().map(item -> Json.read(Json.write(item),PublicEvent.class)).toList();
        return new ChatResponse(Json.write(answer(topic.id(),events,roster,rubric)),request.model().provider(),request.model().model(),ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(50,100),1);
    }
    static BackgroundJob finish(JobService jobs,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!jobs.find(id).terminal() && System.nanoTime()<until) Thread.sleep(10);
        assertTrue(jobs.find(id).terminal()); return jobs.find(id);
    }
    @Test void strictEvidenceCoverageUsesEventOrderRatherThanLexicalIds() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root); var source=evidence();
        var result=answer("topic-1",source.events(),source.roster(),resources.rubric());
        assertEquals(result,LlmResponseValidator.parse(Json.write(result),"topic-1",source.events(),source.roster(),resources.rubric()));
        assertEquals(5,result.participants().get(0).metrics().size());
        String valid=Json.write(result);
        for (String invalid:List.of(valid.replace("turn-11","turn-missing"),valid.replace("turn-11","turn-1"),
                valid.replace("\"topicId\":\"topic-1\"","\"topicId\":\"topic-2\""),valid.replace("\"score\":0","\"score\":5"),
                valid.replace("\"score\":0","\"score\":0.5"),valid.replace("\"participantId\":\"b\"","\"participantId\":\"a\"")))
            assertThrows(IllegalArgumentException.class,() -> LlmResponseValidator.parse(invalid,"topic-1",source.events(),source.roster(),resources.rubric()));
        // turn-10 lexically precedes turn-2 but occurs later: the claimed early response must fail.
        var a=result.participants().get(0); var changed=a.metrics().stream().map(metric -> metric.metricId().equals("responsiveness")
                ? new LlmAssessment.MetricAssessment(metric.metricId(),EvaluationStatus.OK,0,"Premature response",List.of("turn-2","turn-10")) : metric).toList();
        var forged=new LlmAssessment("topic-1",List.of(new LlmAssessment.ParticipantAssessment("a",changed),result.participants().get(1)));
        assertThrows(IllegalArgumentException.class,() -> LlmResponseValidator.parse(Json.write(forged),"topic-1",source.events(),source.roster(),resources.rubric()));
    }
    @Test void oneRepairIsBoundedAndBlankTopicsNeedNoModelCalls() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root); var calls=new AtomicInteger(); List<ChatRequest> requests=new ArrayList<>();
        var evaluator=new LLMEvaluator(request -> {
            requests.add(request);
            return calls.incrementAndGet()==1 ? new ChatResponse("BAD_CANDIDATE","openai","gpt-4o-mini",ChatResponse.CompletionStatus.COMPLETED,null,1) : fake(request,resources.rubric());
        },MODEL,resources);
        var result=evaluator.evaluate(evidence()); assertEquals(2,calls.get()); assertEquals(2,result.topics().get(0).attempts().size());
        assertEquals(EvaluationStatus.OK,result.topics().get(0).status()); assertEquals(EvaluationStatus.INSUFFICIENT_EVIDENCE,result.topics().get(1).status());
        assertEquals(4,requests.get(1).messages().size()); assertTrue(requests.get(0).systemInstructions().contains("untrusted evidence"));
        for (String forbidden:List.of("resolvedPrompts","sourceContents","strategy","grounding","PRIVATE_SETUP_SENTINEL")) assertFalse(Json.write(requests).contains(forbidden));
        calls.set(0); var broken=new LLMEvaluator(request -> { calls.incrementAndGet(); return new ChatResponse("{}","openai","gpt-4o-mini",ChatResponse.CompletionStatus.COMPLETED,null,1); },MODEL,resources);
        assertEquals("invalid_assessment",broken.evaluate(evidence()).topics().get(0).error()); assertEquals(2,calls.get());
    }
    @Test void inputCallAndTokenBudgetsAndRefusalsStopBeforeExtraCalls() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root); var calls=new AtomicInteger();
        engine.ChatManager fake=request -> { calls.incrementAndGet(); return fake(request,resources.rubric()); };
        var small=new LlmEvaluationResources(new LlmEvaluationConfig(1,1,20,1000,100000,81920),resources.rubric(),resources.systemPrompt(),resources.cue(),resources.repairPrompt(),resources.sourceHashes());
        assertEquals("input_budget_exceeded",new LLMEvaluator(fake,MODEL,small).evaluate(evidence()).topics().get(0).error()); assertEquals(0,calls.get());
        var tokens=new LlmEvaluationResources(new LlmEvaluationConfig(1,1,20,100000,100000,1),resources.rubric(),resources.systemPrompt(),resources.cue(),resources.repairPrompt(),resources.sourceHashes());
        assertEquals("generation_budget_exhausted",new LLMEvaluator(fake,MODEL,tokens).evaluate(evidence()).topics().get(0).error()); assertEquals(0,calls.get());
        var limited=new LlmEvaluationResources(new LlmEvaluationConfig(1,1,1,100000,100000,81920),resources.rubric(),resources.systemPrompt(),resources.cue(),resources.repairPrompt(),resources.sourceHashes());
        var source=evidence(); var events=new ArrayList<>(source.events()); events.add(new PublicEvent("turn-12","topic-2",PublicEvent.Type.SPEECH,source.roster().get(0),"Second evidence."));
        var two=new Transcript(2,source.runId(),1L,2L,source.roster(),source.topics(),events,Transcript.Outcome.COMPLETE);
        assertEquals("generation_budget_exhausted",new LLMEvaluator(fake,MODEL,limited).evaluate(two).topics().get(1).error()); assertEquals(1,calls.get());
        calls.set(0); var refused=new LLMEvaluator(request -> { calls.incrementAndGet(); return new ChatResponse(null,"openai","gpt-4o-mini",ChatResponse.CompletionStatus.REFUSED,null,1); },MODEL,resources);
        assertEquals("response_refused",refused.evaluate(source).topics().get(0).error()); assertEquals(1,calls.get());
    }
    @Test void durableJobsFreezeResourcesAndRetainTopicsOnCancellationAndRestart() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root); var source=evidence(); var events=new ArrayList<>(source.events());
        events.add(new PublicEvent("turn-12","topic-2",PublicEvent.Type.SPEECH,source.roster().get(0),"Second evidence."));
        source=new Transcript(2,source.runId(),1L,2L,source.roster(),source.topics(),events,Transcript.Outcome.COMPLETE);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1); var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var service=new LlmEvaluationService(root,jobs,model -> request -> {
                if (calls.incrementAndGet()==2) { entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                assertEquals(resources.systemPrompt()+"\n"+LlmResponseValidator.schema(evidence().roster(),resources.rubric()).json(),request.systemInstructions());
                return fake(request,resources.rubric());
            });
            var job=service.start(source,MODEL); assertTrue(entered.await(5,TimeUnit.SECONDS));
            new AssetService(root).update("prompts/EvaluatorCue.txt","EDITED_NEXT_JOB",true);
            jobs.cancel(job.id()); release.countDown(); assertEquals(BackgroundJob.State.CANCELLED,jobs.find(job.id()).state());
            var report=(LlmReport)jobs.find(job.id()).result(); assertEquals(1,report.assessments().topics().size());
            assertEquals(resources.sourceHashes(),report.assessments().sourceHashes());
            try (var restarted=new JobService(root.resolve("runs/jobs"))) { assertEquals(Json.parse(Json.write(report)),restarted.find(job.id()).result()); }
        }
    }
    @Test void invalidEditableRubricsAndDemoJudgesFailBeforeWork() throws Exception {
        TestFixtures.copyResources(root); var assets=new AssetService(root); String rubric=assets.read("evaluation/rubric.json");
        assertThrows(IllegalArgumentException.class,() -> assets.update("evaluation/rubric.json",rubric.replace("consistency","hidden_intent"),true));
        assertEquals(rubric,assets.read("evaluation/rubric.json"));
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            assertThrows(IllegalArgumentException.class,() -> new LlmEvaluationService(root,jobs,model -> request -> { fail(); return null; }).start(evidence(),new ModelConfig("demo","fixed",null,null,100,5)));
            assertTrue(jobs.list().isEmpty());
        }
    }
    @Test void failedLaterTopicRetainsCompletedAssessmentAndSanitizesProviderException() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root); var source=evidence(); var events=new ArrayList<>(source.events());
        events.add(new PublicEvent("turn-12","topic-2",PublicEvent.Type.SPEECH,source.roster().get(0),"Second evidence."));
        source=new Transcript(2,source.runId(),1L,2L,source.roster(),source.topics(),events,Transcript.Outcome.COMPLETE);
        var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var job=finish(jobs,new LlmEvaluationService(root,jobs,model -> request -> {
                if (calls.incrementAndGet()==2) throw new IllegalStateException("SECRET_EXCEPTION_SENTINEL");
                return fake(request,resources.rubric());
            }).start(source,MODEL).id());
            assertEquals(BackgroundJob.State.FAILED,job.state()); var report=(LlmReport)job.result();
            assertEquals(EvaluationStatus.OK,report.assessments().topics().get(0).status());
            assertEquals("provider_failed",report.assessments().topics().get(1).error());
            assertEquals(2,calls.get()); assertFalse(Json.write(job).contains("SECRET_EXCEPTION_SENTINEL"));
            assertEquals("llm-rubric-schema2-v1",report.assessments().implementationVersion());
            assertTrue(report.assessments().topics().get(0).attempts().get(0).requestSha256().matches("[0-9a-f]{64}"));
        }
    }
    @Test void localContextRefusalHasItsOwnSafeReportCodeAndDoesNotMasqueradeAsProviderFailure() throws Exception {
        TestFixtures.copyResources(root); var resources=LlmEvaluationResources.load(root);
        var local=new ModelConfig("ollama","qwen3:8b",null,null,4096,5);
        var adapter=new engine.provider.OllamaChatManager(java.net.URI.create("http://127.0.0.1:1"),1024);
        var calls=new AtomicInteger();
        try (var jobs=new JobService(root.resolve("runs/jobs"))) {
            var job=finish(jobs,new LlmEvaluationService(root,jobs,model -> request -> {
                if (calls.incrementAndGet()==1) return new ChatResponse("{}","ollama","qwen3:8b",ChatResponse.CompletionStatus.COMPLETED,null,1);
                return adapter.complete(request);
            }).start(evidence(),local).id());
            var report=(LlmReport)job.result(); var topic=report.assessments().topics().get(0);
            assertEquals(BackgroundJob.State.FAILED,job.state()); assertEquals("context_budget_exceeded",topic.error());
            assertEquals(2,calls.get()); assertEquals(2,topic.attempts().size()); assertNull(topic.assessment());
            assertEquals(ChatResponse.CompletionStatus.COMPLETED,topic.attempts().get(0).completionStatus());
            assertNull(topic.attempts().get(1).completionStatus()); assertNull(topic.attempts().get(1).usage());
            try (var restarted=new JobService(root.resolve("runs/jobs"))) {
                assertEquals(Json.parse(Json.write(report)),restarted.find(job.id()).result());
            }
        }
        var untrusted=new LLMEvaluator(request -> { throw new IllegalArgumentException("Request exceeds the conservative Ollama context budget"); },local,resources);
        assertEquals("provider_failed",untrusted.evaluate(evidence()).topics().get(0).error());
    }
    /** Deliberately fake loopback backend for browser acceptance; no provider/network calls. */
    public static void main(String[] args) throws Exception {
        Path root=Path.of("target/llm-ui-fixture-"+UUID.randomUUID()); TestFixtures.copyResources(root);
        var resources=LlmEvaluationResources.load(root);
        var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> fake(request,resources.rubric()));
        var run=app.importTranscript(evidence()); WebServer.start(18088,app);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("Fake evaluator UI at http://localhost:18088; synthetic run "+run.id());
    }
}
