import engine.ChatManager;
import engine.application.*;
import engine.chat.*;
import engine.config.*;
import engine.evaluation.*;
import engine.evaluation.llm.*;
import engine.provider.*;
import engine.transcript.*;
import engine.utils.*;
import java.nio.file.*;
import java.util.*;

/** One explicit, approved technical replacement per named failed adversarial cell. */
public final class RepairAdversarialStudy {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !Set.of("repair-approved","self-test-repair").contains(args[0])) throw new IllegalArgumentException("Use repair-approved or self-test-repair REPOSITORY BATCH");
        boolean fake=args[0].equals("self-test-repair");
        Path repo=Path.of(args[1]).toAbsolutePath().normalize(),batch=Path.of(args[2]).toAbsolutePath().normalize(),root=batch.resolve("application");
        if(fake && (!batch.startsWith(repo.resolve("target")) || !batch.getFileName().toString().contains("fake"))) throw new IllegalStateException("Synthetic repair must use an isolated fake fixture");
        var manifest=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("manifest.json")));
        var plan=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("operations/repair-plan.json")));
        if(!"COLLECTED".equals(manifest.get("state")) || !"adversarial-topic-v1".equals(manifest.get("treatmentId"))) throw new IllegalStateException("Inspect completed adversarial first pass before replacement");
        if(!Objects.equals(plan.get("batchId"),manifest.get("batchId")) || !Objects.equals(plan.get("approvalState"),fake?"SYNTHETIC_TEST_ONLY":"APPROVED_BY_USER")) throw new IllegalStateException("Explicit approval of the exact replacement plan required");
        Path ledgerFile=batch.resolve("operations/usage.json");
        if(!Hashes.sha256(Files.readAllBytes(ledgerFile)).equals(plan.get("originalUsageSha256"))) throw new IllegalStateException("Replacement plan ledger changed; inspect before dispatch");
        if(!"prepaid-account".equals(manifest.get("budgetMode")) || manifest.get("budgetLimitUsd")!=null) throw new IllegalStateException("Replacement must retain the approved prepaid policy");
        for(var e:((Map<String,String>)manifest.get("frozenSourceHashes")).entrySet())
            if(!Hashes.sha256(Files.readAllBytes(root.resolve(e.getKey()))).equals(e.getValue())) throw new IllegalStateException("Frozen replacement source changed");
        if(manifest.get("previousBatch") instanceof String previous && !Hashes.sha256(Files.readAllBytes(Path.of(previous).resolve("operations/usage.json"))).equals(manifest.get("previousUsageSha256"))) throw new IllegalStateException("Previous accounted ledger changed");
        var approvals=(List<Map<String,Object>>)plan.get("cells");
        if(approvals==null || approvals.isEmpty()) throw new IllegalStateException("No failed cells approved");
        var cells=(List<Map<String,Object>>)manifest.get("cells");
        var approved=new LinkedHashMap<String,Map<String,Object>>();
        var store=new RunStore(root.resolve("runs"));
        for(var item:approvals) {
            String name=(String)item.get("cellId");
            if(approved.put(name,item)!=null) throw new IllegalStateException("Duplicate replacement approval");
            var cell=cells.stream().filter(c -> name.equals(c.get("cellId"))).findFirst().orElseThrow();
            if(!"COMPLETE_WITH_FAILURES".equals(cell.get("state")) || "COMPLETE".equals(cell.get("generationOutcome")) || cell.containsKey("previousRunIds") || !Objects.equals(cell.get("runId"),item.get("runId"))) throw new IllegalStateException("Only the exact failed original run can be replaced once");
            if(((Number)item.get("maxGenerationCalls")).intValue()!=25 || ((Number)item.get("maxJudgeCalls")).intValue()!=2) throw new IllegalStateException("Replacement must use the original bounded call counts");
            var transcript=store.transcript((String)cell.get("runId"));
            if(transcript.outcome()==Transcript.Outcome.COMPLETE || transcript.events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count()>=25) throw new IllegalStateException("Cannot replace complete public evidence");
            if(!Hashes.sha256(Files.readAllBytes(batch.resolve("public/transcripts/"+name+".json"))).equals(item.get("transcriptSha256"))) throw new IllegalStateException("Approved original transcript changed");
        }
        var ledger=new CollectEvaluationStudy.Ledger(ledgerFile,null);
        ledger.calls.addAll((List<Map<String,Object>>)((Map<String,Object>)Json.parse(Files.readString(ledgerFile))).get("calls"));
        ledger.boundViolated=ledger.calls.stream().anyMatch(c -> "USAGE_EXCEEDS_RESERVED_BOUND".equals(c.get("status")));
        int initialCalls=ledger.calls.size();
        var snapshot=ConfigurationSnapshot.load(root);
        Process runtime=null;
        try {
            if(!fake) {
                CollectEvaluationStudy.requireFree(8766);
                if(approvals.stream().anyMatch(a -> cells.stream().anyMatch(c -> c.get("cellId").equals(a.get("cellId")) && "study-qwen".equals(c.get("modelPreset"))))) {
                    for(String key:List.of("OLLAMA_BASE_URL","OLLAMA_CONTEXT_TOKENS")) if(System.getenv(key)!=null && !System.getenv(key).isBlank()) throw new IllegalStateException("Ambient Ollama override refused");
                    CollectEvaluationStudy.requireFree(11451);
                    for(String name:List.of("ollama.log","runtime-process.json")) {
                        Path original=batch.resolve("operations/"+name),archive=batch.resolve("operations/first-collection-"+name);
                        if(Files.exists(original) && !Files.exists(archive)) Files.copy(original,archive);
                    }
                    runtime=CollectEvaluationStudy.startRuntime(manifest,batch);Thread.sleep(1500);
                }
            }
            var managed=new ManagedOllama(root);var factory=new ProviderFactory(repo,managed);
            java.util.function.BiFunction<ModelConfig,OllamaConfig,ChatManager> providers=(model,config) -> {
                ChatManager delegate=fake?request -> {
                    try {Thread.sleep(350);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic interrupted");}
                    return ledger.purpose.equals("rubric")?fakeJudge(request,root):new ChatResponse("Mr Speaker, this synthetic replacement verifies persistence and scheduling only.",model.provider(),model.model(),ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,25),350);
                }:factory.forModel(model,config);
                return request -> {
                    var cell=cells.stream().filter(c -> ledger.cell.equals(c.get("cellId"))).findFirst().orElseThrow();
                    if(!approved.containsKey(ledger.cell) || !Set.of("generation","rubric").contains(ledger.purpose)) throw new IllegalStateException("Request outside the approved replacement");
                    var expected=snapshot.config().models().get(ledger.purpose.equals("rubric")?"study-judge":(String)cell.get("modelPreset"));
                    if(!request.model().equals(expected)) throw new IllegalStateException("Frozen replacement model/settings changed");
                    long dispatched=ledger.calls.subList(initialCalls,ledger.calls.size()).stream().filter(c -> ledger.cell.equals(c.get("cellId")) && ledger.purpose.equals(c.get("purpose"))).count();
                    if(dispatched>=(ledger.purpose.equals("generation")?25:2)) throw new IllegalStateException("Approved replacement call cap reached");
                    return ledger.wrap(delegate).complete(request);
                };
            };
            try(var app=new DebateApplication(root,store,providers,null,managed)) {
                manifest.put("state","REPAIRING_APPROVED");CollectEvaluationStudy.saveManifest(batch,manifest);
                boolean failed=false;
                for(var item:approvals) {
                    if(Files.exists(batch.resolve("STOP"))) throw new IllegalStateException("Explicit replacement stop before generation");
                    String name=(String)item.get("cellId");var cell=cells.stream().filter(c -> name.equals(c.get("cellId"))).findFirst().orElseThrow();
                    String original=(String)cell.get("runId");var settings=store.setup(original).settings();
                    if(settings.rounds()!=5 || settings.members().size()!=5 || settings.topics().size()!=1) throw new IllegalStateException("Original replacement design changed");
                    for(String relative:List.of("transcripts/"+name+".json","transcripts/"+name+".txt","reports/"+name+"-local.json","reports/"+name+"-rubric.json")) {
                        Path source=batch.resolve("public/"+relative),destination=batch.resolve("public/archived-attempts/"+original+"/"+source.getFileName());
                        if(Files.exists(source)) {Files.createDirectories(destination.getParent());Files.copy(source,destination);if(!Hashes.sha256(Files.readAllBytes(source)).equals(Hashes.sha256(Files.readAllBytes(destination)))) throw new IllegalStateException("Original archive mismatch");}
                    }
                    cell.put("previousRunIds",List.of(original));cell.put("previousLocalJobId",cell.get("localJobId"));cell.put("previousRubricJobId",cell.get("rubricJobId"));
                    ledger.cell=name;ledger.purpose="generation";
                    var session=app.start(settings.settings());cell.put("runId",session.id());CollectEvaluationStudy.saveManifest(batch,manifest);
                    while(!session.isFinished()) {if(Files.exists(batch.resolve("STOP"))) session.adjourn();Thread.sleep(500);}
                    var transcript=session.transcript();String json=Json.write(transcript);
                    long speeches=transcript.events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count();
                    cell.put("generationOutcome",transcript.outcome().name());cell.put("speechCount",speeches);cell.put("startedAt",transcript.startedAt());cell.put("endedAt",transcript.endedAt());cell.put("transcriptSha256",Hashes.sha256(json));
                    AtomicFiles.write(batch.resolve("public/transcripts/"+name+".json"),json);AtomicFiles.write(batch.resolve("public/transcripts/"+name+".txt"),app.textExport(session.id()));
                    if(Files.exists(batch.resolve("STOP"))) throw new IllegalStateException("Explicit replacement stop");
                    if(!fake) {
                        var local=app.evaluate(session.id(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance")));cell.put("localJobId",local.id());local=CollectEvaluationStudy.waitJob(app,local.id());cell.put("localJobState",local.state().name());
                        AtomicFiles.write(batch.resolve("public/reports/"+name+"-local.json"),Json.write(local.result()));
                        if(local.result() instanceof engine.evaluation.local.LocalReport report) for(var method:report.methods()) for(int i=0;i<method.batches().size();i++) AtomicFiles.write(batch.resolve("public/raw-evaluation/"+name+"-"+method.methodId()+"-replacement-batch-"+(i+1)+".json"),Json.write(method.batches().get(i)));
                    }
                    ledger.purpose="rubric";var judge=app.evaluateLlm(session.id(),Map.of("modelPreset","study-judge"));cell.put("rubricJobId",judge.id());judge=CollectEvaluationStudy.waitJob(app,judge.id());cell.put("rubricJobState",judge.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+name+"-rubric.json"),Json.write(judge.result()));
                    boolean complete=transcript.outcome()==Transcript.Outcome.COMPLETE && speeches==25 && judge.state()==BackgroundJob.State.COMPLETE && (fake || "COMPLETE".equals(cell.get("localJobState")));
                    failed|=!complete;cell.put("state",fake?"SYNTHETIC_REPAIRED":complete?"COMPLETE":"COMPLETE_WITH_FAILURES");CollectEvaluationStudy.saveManifest(batch,manifest);
                    System.out.println("APPROVED_REPLACEMENT "+name+" "+cell.get("state"));
                }
                manifest.put("state",fake?"SYNTHETIC_REPAIR_VERIFIED":failed?"REPLACEMENT_FAILED":"ANALYZED");manifest.put("budgetCommittedUsd",ledger.committed());CollectEvaluationStudy.saveManifest(batch,manifest);
                if(fake && failed) throw new AssertionError("Synthetic replacement incomplete");
            }
        } finally {
            if(runtime!=null) {var children=runtime.descendants().toList();runtime.destroy();if(!runtime.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) runtime.destroyForcibly();for(var child:children) if(child.isAlive()) child.destroy();}
        }
    }
    @SuppressWarnings("unchecked")
    static ChatResponse fakeJudge(ChatRequest request,Path root) {
        var body=(Map<String,Object>)Json.parse(request.messages().get(0).content());
        var topic=Json.read(Json.write(body.get("topic")),Topic.class);
        var roster=((List<Object>)body.get("participants")).stream().map(p -> Json.read(Json.write(p),Participant.class)).toList();
        var events=((List<Object>)body.get("events")).stream().map(e -> Json.read(Json.write(e),PublicEvent.class)).toList();
        var rubric=LlmEvaluationResources.load(root).rubric();
        var answer=new LlmAssessment(topic.id(),roster.stream().map(person -> {
            var own=events.stream().filter(e -> e.speaker()!=null && e.speaker().id().equals(person.id())).toList();
            var prior=events.stream().filter(e -> e.speaker()!=null && !e.speaker().id().equals(person.id()) && events.indexOf(e)<events.indexOf(own.get(own.size()-1))).findFirst();
            return new LlmAssessment.ParticipantAssessment(person.id(),rubric.metrics().stream().map(metric -> {
                var refs=new ArrayList<>(own.stream().map(PublicEvent::id).toList());if(metric.requiresPriorOtherSpeaker()) refs.add(prior.orElseThrow().id());
                return new LlmAssessment.MetricAssessment(metric.id(),EvaluationStatus.OK,metric.minimum(),"Synthetic replacement fixture; not a research judgment.",refs);
            }).toList());
        }).toList());
        return new ChatResponse(Json.write(answer),request.model().provider(),request.model().model(),ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,100),350);
    }
}
