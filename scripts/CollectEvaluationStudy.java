import engine.ChatManager;
import engine.application.*;
import engine.chat.*;
import engine.config.*;
import engine.provider.*;
import engine.transcript.*;
import engine.utils.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit study driver around the same backend used by HTTP/CLI, with no alternate debate engine. */
public final class CollectEvaluationStudy {
    private static final double INPUT_RATE = .275 / 1_000_000, OUTPUT_RATE = 1.10 / 1_000_000;
    static final class Ledger {
        final Path file;
        final Double limit;
        final List<Map<String,Object>> calls = new ArrayList<>();
        boolean boundViolated;
        volatile String cell = "self-test", purpose = "generation";
        Ledger(Path file, Double limit) { this.file=file; this.limit=limit; }
        synchronized double committed() { return calls.stream().mapToDouble(c -> ((Number)c.get("budgetUsd")).doubleValue()).sum(); }
        static long bound(ChatRequest request) {
            return Json.write(Map.of("messages", ChatCompletionsProvider.messages(request,true), "schema",request.outputSchema()==null ? Map.of() : request.outputSchema().value()))
                .getBytes(StandardCharsets.UTF_8).length + 4096L;
        }
        ChatManager wrap(ChatManager delegate) {
            return request -> {
                long inputBound=bound(request);
                boolean paid=request.model().provider().equals("openai");
                if (paid && (!request.model().model().equals("gpt-6-luna") || inputBound>=272000))
                    throw new IllegalStateException("Study pricing/context condition refused before dispatch");
                double reservation=paid ? inputBound*INPUT_RATE+request.model().maxCompletionTokens()*OUTPUT_RATE : 0;
                Map<String,Object> entry=new LinkedHashMap<>();
                synchronized(this) {
                    if (paid && boundViolated) throw new IllegalStateException("Study usage bound violated; further paid dispatch refused");
                    if (limit!=null && committed()+reservation>limit) throw new IllegalStateException("Study fixed USD budget admission refused before dispatch");
                    entry.put("callId",calls.size()+1); entry.put("cellId",cell); entry.put("purpose",purpose);
                    entry.put("provider",request.model().provider()); entry.put("model",request.model().model());
                    entry.put("settings",request.model()); entry.put("startedAt",System.currentTimeMillis());
                    entry.put("inputTokenBound",inputBound); entry.put("outputTokenCap",request.model().maxCompletionTokens());
                    entry.put("requestSha256",Hashes.sha256(Json.writeCanonical(request)));
                    entry.put("budgetUsd",reservation); entry.put("standardPriceEstimateUsd",null);
                    entry.put("status","DISPATCH_RESERVED"); calls.add(entry); save();
                }
                long start=System.nanoTime();
                try {
                    ChatResponse response=delegate.complete(request);
                    synchronized(this) {
                        if (purpose.equals("rubric") && response.text()!=null) {
                            AtomicFiles.write(file.getParent().getParent().resolve("public/raw-evaluation/"+cell+"-judge-call-"+entry.get("callId")+".json"),response.text());
                            entry.put("rawReplySha256",Hashes.sha256(response.text()));
                        }
                        entry.put("status",response.status().name()); entry.put("usage",response.usage());
                        entry.put("latencyMillis",response.latencyMillis()); entry.put("endedAt",System.currentTimeMillis());
                        if (paid && response.usage()!=null) {
                            var usage=response.usage();
                            if (usage.inputTokens()>inputBound || usage.outputTokens()>request.model().maxCompletionTokens()) {
                                boundViolated=true;
                                entry.put("status","USAGE_EXCEEDS_RESERVED_BOUND");
                                entry.put("budgetUsd",usage.inputTokens()*INPUT_RATE+usage.outputTokens()*OUTPUT_RATE); save();
                                throw new IllegalStateException("Reported usage exceeds study bound; stop all further paid calls");
                            }
                            entry.put("budgetUsd",usage.inputTokens()*INPUT_RATE+usage.outputTokens()*OUTPUT_RATE);
                            entry.put("standardPriceEstimateUsd",(usage.inputTokens()*.10+usage.outputTokens()*.50)/1_000_000);
                        }
                        save();
                        System.out.printf(Locale.ROOT,"CALL %s %s %s #%s %s %dms budgetUSD=%.6f%n",entry.get("cellId"),entry.get("purpose"),entry.get("provider"),entry.get("callId"),entry.get("status"),response.latencyMillis(),committed());
                    }
                    return response;
                } catch (RuntimeException e) {
                    synchronized(this) {
                        if (entry.get("status").equals("DISPATCH_RESERVED")) entry.put("status","ERROR_"+e.getClass().getSimpleName());
                        if (e.getMessage()!=null && e.getMessage().matches("OpenAI request failed \\(HTTP [0-9]{3}\\)")) entry.put("providerError",e.getMessage());
                        entry.put("endedAt",System.currentTimeMillis()); entry.put("latencyMillis",(System.nanoTime()-start)/1_000_000);
                        save();
                    }
                    System.out.println("CALL_FAILURE "+cell+" "+purpose+" "+e.getClass().getSimpleName());
                    throw e;
                }
            };
        }
        synchronized void save() {
            var value=new LinkedHashMap<String,Object>();
            value.put("schemaVersion",1);value.put("budgetLimitUsd",limit);
            value.put("budgetMode",limit==null?"prepaid-account":"fixed-usd");
            value.put("budgetCommittedUsd",committed());value.put("calls",calls);
            AtomicFiles.write(file,Json.write(value));
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length==2 && args[0].equals("self-test")) { selfTest(Path.of(args[1])); return; }
        if(args.length==3 && args[0].equals("evaluate-local")) { recoverLocal(Path.of(args[2]).toAbsolutePath()); return; }
        if(args.length==3 && Set.of("repair-local-debates","collect-undispatched").contains(args[0])) { repairLocalDebates(Path.of(args[1]).toAbsolutePath(),Path.of(args[2]).toAbsolutePath(),args[0].equals("collect-undispatched")); return; }
        if (args.length!=3 || !Set.of("collect","self-test-matrix").contains(args[0])) throw new IllegalArgumentException("Use collect or self-test-matrix REPOSITORY BATCH, or self-test OUTPUT");
        boolean fake=args[0].equals("self-test-matrix");
        Path repo=Path.of(args[1]).toAbsolutePath(), batch=Path.of(args[2]).toAbsolutePath(), root=batch.resolve("application");
        var manifest=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("manifest.json")));
        if (!manifest.get("state").equals("PREPARED") || Files.exists(batch.resolve("operations/usage.json")))
            throw new IllegalStateException("Already-started batch; inspect retained evidence, never restart inference automatically");
        boolean prepaid="prepaid-account".equals(manifest.get("budgetMode"));
        if(prepaid && (manifest.get("budgetLimitUsd")!=null || manifest.get("cumulativeBudgetLimitUsd")!=null))
            throw new IllegalStateException("Prepaid-account policy must explicitly omit the fixed USD gate");
        if(!prepaid && !(manifest.get("budgetLimitUsd") instanceof Number))
            throw new IllegalStateException("Fixed study budget missing");
        if(manifest.get("previousBatch") instanceof String prior) {
            Path previous=Path.of(prior);
            if(!Hashes.sha256(Files.readAllBytes(previous.resolve("operations/usage.json"))).equals(manifest.get("previousUsageSha256")))
                throw new IllegalStateException("Prior study ledger changed; recompute remaining allowance before dispatch");
            if(!prepaid) {
                double total=((Number)manifest.get("priorBudgetCommittedUsd")).doubleValue()+((Number)manifest.get("budgetLimitUsd")).doubleValue();
                if(total>4.5+1e-12) throw new IllegalStateException("Cumulative study budget exceeds USD4.50");
            }
        }
        for (var e:((Map<String,String>)manifest.get("frozenSourceHashes")).entrySet())
            if (!Hashes.sha256(Files.readAllBytes(root.resolve(e.getKey()))).equals(e.getValue())) throw new IllegalStateException("Frozen study source changed");
        var ledger=new Ledger(batch.resolve("operations/usage.json"),prepaid?null:((Number)manifest.get("budgetLimitUsd")).doubleValue());
        Process runtime=null;
        try {
            if (!fake) {
                for(String name:List.of("OLLAMA_BASE_URL","OLLAMA_CONTEXT_TOKENS"))
                    if (System.getenv(name)!=null && !System.getenv(name).isBlank()) throw new IllegalStateException("Remove ambient Ollama override before collecting frozen study");
                requireFree(11451); requireFree(8766);
                runtime=startRuntime(manifest,batch);
                var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                Map<String,Object> inventory=null;
                for (int i=0;i<300 && runtime.isAlive();i++) {
                    try { inventory=(Map<String,Object>)Json.parse(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11451/api/tags")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).body()); break; }
                    catch(Exception e) { Thread.sleep(200); }
                }
                if (inventory==null) throw new IllegalStateException("Cached Ollama did not become ready");
                var models=(List<Map<String,Object>>)inventory.get("models");
                var qwen=models.stream().filter(m -> "qwen3:8b".equals(m.get("name"))).findFirst().orElseThrow();
                manifest.put("observedOllamaModel",qwen);
                manifest.put("observedOllamaVersion",Json.parse(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11451/api/version")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
                var show=(Map<String,Object>)Json.parse(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:11451/api/show")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"qwen3:8b\"}")).build(),HttpResponse.BodyHandlers.ofString()).body());
                var modelInfo=(Map<String,Object>)show.get("model_info");
                manifest.put("observedNativeContextTokens",modelInfo.get("qwen3.context_length"));
                manifest.put("observedThinkingControl",show.get("thinking"));
                if (!Boolean.FALSE.equals(OllamaConfig.load(root).thinkingEnabled())) throw new IllegalStateException("Requested disabled Qwen thinking must be frozen before collection");
                String key=System.getenv("OPENAI_API_KEY");
                if (key==null || key.isBlank()) key=Files.readString(repo.resolve("keys/openAi/OpenAI_Key.txt")).trim();
                var access=http.send(HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models/gpt-6-luna")).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+key.trim()).GET().build(),HttpResponse.BodyHandlers.discarding());
                manifest.put("openaiReadOnlyAccessStatus",access.statusCode());
                saveManifest(batch,manifest);
                if (access.statusCode()!=200) throw new IllegalStateException("OpenAI selected model access check failed; no generation request made");
            }
            var managed=new ManagedOllama(root);
            var factory=new ProviderFactory(repo,managed);
            java.util.function.BiFunction<ModelConfig,OllamaConfig,ChatManager> providers=(model,config) -> ledger.wrap(fake
                ? request -> { try { Thread.sleep(350); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fake interrupted"); }
                    checkFakePrivacy(request,manifest,root,ledger);
                    return new ChatResponse("Mr Speaker, this is a synthetic harness speech about the topic. This text verifies scheduling only.",model.provider(),model.model(),ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,25),350); }
                : factory.forModel(model,config));
            try(var app=new DebateApplication(root,new RunStore(root.resolve("runs")),providers,null,managed)) {
                manifest.put("state","RUNNING"); manifest.put("startedAt",System.currentTimeMillis()); saveManifest(batch,manifest);
                for (var cell:(List<Map<String,Object>>)manifest.get("cells")) {
                    if(Files.exists(batch.resolve("STOP"))) throw new IllegalStateException("Explicit study stop file");
                    ledger.cell=(String)cell.get("cellId"); ledger.purpose="generation";
                    System.out.println("START_CELL "+ledger.cell);
                    cell.put("state","GENERATING"); saveManifest(batch,manifest);
                    var session=app.start((Map<String,Object>)cell.get("settings"));
                    cell.put("runId",session.id()); saveManifest(batch,manifest);
                    long displayed=-1;
                    while (!session.isFinished()) {
                        if(Files.exists(batch.resolve("STOP"))) session.adjourn();
                        long count=session.transcript().events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count();
                        if(count!=displayed) { displayed=count; cell.put("speechCount",count); saveManifest(batch,manifest); }
                        Thread.sleep(500);
                    }
                    var transcript=session.transcript();
                    long speeches=transcript.events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count();
                    cell.put("generationOutcome",transcript.outcome().name()); cell.put("speechCount",speeches);
                    cell.put("startedAt",transcript.startedAt()); cell.put("endedAt",transcript.endedAt());
                    String publicJson=Json.write(transcript);
                    cell.put("transcriptSha256",Hashes.sha256(publicJson));
                    AtomicFiles.write(batch.resolve("public/transcripts/"+ledger.cell+".json"),publicJson);
                    AtomicFiles.write(batch.resolve("public/transcripts/"+ledger.cell+".txt"),app.textExport(session.id()));
                    if(Files.exists(batch.resolve("STOP"))) throw new IllegalStateException("Explicit study stop file");
                    if (fake) {
                        if (transcript.outcome()!=Transcript.Outcome.COMPLETE || speeches!=25 || transcript.roster().size()!=5 || transcript.events().stream().anyMatch(e -> e.type()==PublicEvent.Type.INTERJECTION)) throw new AssertionError("Matrix count/ordinary speaking failure");
                        ledger.purpose="rubric";
                        var judge=app.evaluateLlm(session.id(),Map.of("modelPreset","study-judge"));
                        waitJob(app,judge.id());
                        long blindCalls=ledger.calls.stream().filter(c -> ledger.cell.equals(c.get("cellId")) && "rubric".equals(c.get("purpose"))).count();
                        if(blindCalls!=2) throw new AssertionError("Fake invalid judge response must exercise both bounded blind attempts");
                        cell.put("verifiedBlindJudgeCalls",blindCalls);
                        cell.put("state","SYNTHETIC_COMPLETE"); saveManifest(batch,manifest); continue;
                    }
                    cell.put("state","EVALUATING_LOCAL"); saveManifest(batch,manifest);
                    var local=app.evaluate(session.id(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance")));
                    cell.put("localJobId",local.id()); saveManifest(batch,manifest);
                    local=waitJob(app,local.id());
                    cell.put("localJobState",local.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+ledger.cell+"-local.json"),Json.write(local.result()));
                    if(local.result() instanceof engine.evaluation.local.LocalReport report) {
                        for(var method:report.methods()) for(int bi=0;bi<method.batches().size();bi++)
                            AtomicFiles.write(batch.resolve("public/raw-evaluation/"+ledger.cell+"-"+method.methodId()+"-batch-"+(bi+1)+".json"),Json.write(method.batches().get(bi)));
                    }
                    AtomicFiles.write(batch.resolve("operations/"+ledger.cell+"-local-job.json"),Json.write(local));
                    ledger.purpose="rubric"; cell.put("state","EVALUATING_RUBRIC"); saveManifest(batch,manifest);
                    var judge=app.evaluateLlm(session.id(),Map.of("modelPreset","study-judge"));
                    cell.put("rubricJobId",judge.id()); saveManifest(batch,manifest);
                    judge=waitJob(app,judge.id());
                    cell.put("rubricJobState",judge.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+ledger.cell+"-rubric.json"),Json.write(judge.result()));
                    AtomicFiles.write(batch.resolve("operations/"+ledger.cell+"-rubric-job.json"),Json.write(judge));
                    cell.put("state",transcript.outcome()==Transcript.Outcome.COMPLETE && speeches==25 && local.state()==BackgroundJob.State.COMPLETE && judge.state()==BackgroundJob.State.COMPLETE ? "COMPLETE" : "COMPLETE_WITH_FAILURES");
                    cell.put("budgetCommittedUsd",ledger.committed()); saveManifest(batch,manifest);
                    System.out.println("END_CELL "+ledger.cell+" "+cell.get("state"));
                }
                manifest.put("state",fake ? "SYNTHETIC_VERIFIED" : "COLLECTED"); manifest.put("endedAt",System.currentTimeMillis()); manifest.put("budgetCommittedUsd",ledger.committed()); saveManifest(batch,manifest);
            }
        } catch (Exception | AssertionError e) {
            manifest.put("state","STOPPED_REQUIRES_INSPECTION"); manifest.put("stopCategory",e.getClass().getSimpleName()); saveManifest(batch,manifest);
            System.err.println("STUDY_STOPPED "+e.getClass().getSimpleName()+" "+(e.getMessage()!=null && (e.getMessage().startsWith("OpenAI selected") || e.getMessage().startsWith("Already-started")) ? e.getMessage() : "Inspect retained study state"));
            throw e;
        } finally {
            if(runtime!=null) { runtime.destroy(); if(!runtime.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) runtime.destroyForcibly(); }
        }
    }
    static void requireFree(int port) throws Exception { try(var socket=new java.net.ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1",port)); } }
    @SuppressWarnings("unchecked")
    static Process startRuntime(Map<String,Object> manifest,Path batch) throws Exception {
        var builder=new ProcessBuilder((String)manifest.get("ollamaExecutable"),"serve").redirectErrorStream(true).redirectOutput(batch.resolve("operations/ollama.log").toFile());
        Files.createDirectories(batch.resolve("operations"));
        builder.environment().keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("OLLAMA_") || Set.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","HF_TOKEN").contains(k.toUpperCase(Locale.ROOT)));
        builder.environment().putAll((Map<String,String>)manifest.get("ollamaEnvironment"));
        builder.environment().put("OLLAMA_HOST","http://127.0.0.1:11451"); builder.environment().put("OLLAMA_MODELS",(String)manifest.get("ollamaModels"));
        builder.environment().put("OLLAMA_DEBUG_LOG_REQUESTS","false");
        Path profile=batch.resolve("operations/ollama-profile"); Files.createDirectories(profile);
        builder.environment().put("USERPROFILE",profile.toString());
        var process=builder.start();
        AtomicFiles.write(batch.resolve("operations/runtime-process.json"),Json.write(Map.of("pid",process.pid(),"executable",manifest.get("ollamaExecutable"),"port",11451)));
        return process;
    }
    static BackgroundJob waitJob(DebateApplication app,String id) throws Exception { while(!app.background().find(id).terminal()) Thread.sleep(500); return app.background().find(id); }
    @SuppressWarnings("unchecked")
    static void repairLocalDebates(Path repo,Path batch,boolean undispatched) throws Exception {
        Set<String> approved=undispatched ? Set.of("four_day_week_h10_study-luna_r3") : Set.of("four_day_week_h0_study-qwen_r1","four_day_week_h10_study-qwen_r1","nuclear_power_h10_study-qwen_r1","nuclear_power_h100_study-qwen_r1");
        var manifest=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("manifest.json")));
        if(!Set.of("COLLECTED","ANALYZED").contains(manifest.get("state"))) throw new IllegalStateException("Inspect completed original collection before explicit replacement");
        Path root=batch.resolve("application");
        for(var e:((Map<String,String>)manifest.get("frozenSourceHashes")).entrySet())
            if(!Hashes.sha256(Files.readAllBytes(root.resolve(e.getKey()))).equals(e.getValue())) throw new IllegalStateException("Frozen replacement source changed");
        var snapshot=ConfigurationSnapshot.load(root); var judgeModel=snapshot.config().models().get("study-judge");
        var ledger=new Ledger(batch.resolve("operations/usage.json"),Math.min(4.5,((Number)manifest.get("budgetLimitUsd")).doubleValue()));
        var stored=(Map<String,Object>)Json.parse(Files.readString(ledger.file));ledger.calls.addAll((List<Map<String,Object>>)stored.get("calls"));
        ledger.boundViolated=ledger.calls.stream().anyMatch(c -> "USAGE_EXCEEDS_RESERVED_BOUND".equals(c.get("status")));
        var store=new RunStore(root.resolve("runs"));
        var generatorModel=snapshot.config().models().get("study-luna");
        if(undispatched) {
            var incomplete=((List<Map<String,Object>>)manifest.get("cells")).stream().filter(c -> !"COMPLETE".equals(c.get("generationOutcome"))).toList();
            if(incomplete.size()!=1 || !approved.contains(incomplete.get(0).get("cellId"))) throw new IllegalStateException("Only the exact missing scheduled condition can be dispatched");
            var missing=incomplete.get(0);
            if(missing.containsKey("previousRunIds") || ledger.calls.stream().anyMatch(c -> Objects.equals(c.get("cellId"),missing.get("cellId")))) throw new IllegalStateException("Cannot repeat a previously dispatched condition");
            if(store.transcript((String)missing.get("runId")).events().stream().anyMatch(e -> e.type()==PublicEvent.Type.SPEECH)) throw new IllegalStateException("Missing condition already contains speeches");
            if(!Hashes.sha256(Files.readAllBytes(Path.of((String)manifest.get("previousBatch")).resolve("operations/usage.json"))).equals(manifest.get("previousUsageSha256"))) throw new IllegalStateException("Prior budget ledger changed");
            if(ledger.limit+((Number)manifest.get("priorBudgetCommittedUsd")).doubleValue()>4.5+1e-12) throw new IllegalStateException("Cumulative budget exceeds USD4.50");
        }
        long priorPaid=ledger.calls.stream().filter(c -> "openai".equals(c.get("provider"))).count();
        int priorCalls=ledger.calls.size();
        requireFree(11451);requireFree(8766);
        for(String file:List.of("ollama.log","runtime-process.json")) {
            Path original=batch.resolve("operations/"+file),archive=batch.resolve("operations/first-collection-"+file);
            if(Files.exists(original) && !Files.exists(archive)) Files.copy(original,archive);
        }
        Process runtime=startRuntime(manifest,batch);
        try {
            Thread.sleep(1500);
            var managed=new ManagedOllama(root);var factory=new ProviderFactory(repo,managed);
            java.util.function.BiFunction<ModelConfig,OllamaConfig,ChatManager> providers=(model,config) -> {
                if(ledger.purpose.equals("generation") && (undispatched ? !model.equals(generatorModel) : !model.provider().equals("ollama"))) throw new IllegalStateException("Generator outside the admitted scope");
                if(ledger.purpose.equals("rubric") && !model.equals(judgeModel)) throw new IllegalStateException("Only approved common judge may be called");
                var wrapped=ledger.wrap(factory.forModel(model,config));
                return request -> {
                    if(undispatched && ledger.calls.subList(priorCalls,ledger.calls.size()).stream().filter(c -> ledger.purpose.equals(c.get("purpose"))).count()>=(ledger.purpose.equals("generation")?25:2))
                        throw new IllegalStateException("Missing condition purpose-specific call limit reached");
                    if(model.provider().equals("openai") && ledger.calls.stream().filter(c -> "openai".equals(c.get("provider"))).count()-priorPaid>=(undispatched?27:8))
                        throw new IllegalStateException("Admitted call limit reached");
                    return wrapped.complete(request);
                };
            };
            try(var app=new DebateApplication(root,store,providers,null,managed)) {
                boolean failed=false;
                for(var cell:(List<Map<String,Object>>)manifest.get("cells")) {
                    if("COMPLETE".equals(cell.get("generationOutcome"))) continue;
                    String name=(String)cell.get("cellId"),previous=(String)cell.get("runId");
                    if(!approved.contains(name) || cell.containsKey("previousRunIds")) throw new IllegalStateException("Cell outside the approved one-replacement scope");
                    var original=store.setup(previous).settings();
                    if(undispatched && (original.rounds()!=5 || original.topics().size()!=1 || original.members().size()!=5 || original.groundingCount()!=10)) throw new IllegalStateException("Missing condition sitting settings changed");
                    if(original.members().stream().anyMatch(member -> !snapshot.config().models().get(member.modelPreset()).provider().equals(undispatched?"openai":"ollama")))
                        throw new IllegalStateException("Generator provider outside the admitted scope");
                    if(app.find(previous).transcript().outcome()==Transcript.Outcome.COMPLETE) throw new IllegalStateException("Cannot repeat a completed debate");
                    for(String relative:List.of("transcripts/"+name+".json","transcripts/"+name+".txt","reports/"+name+"-local.json","reports/"+name+"-rubric.json")) {
                        Path file=batch.resolve("public/"+relative);
                        if(Files.exists(file)) {Path target=batch.resolve("public/archived-attempts/"+previous+"/"+file.getFileName());Files.createDirectories(target.getParent());if(!Files.exists(target)) Files.copy(file,target);}
                    }
                    cell.put("previousRunIds",List.of(previous));cell.put("previousLocalJobId",cell.get("localJobId"));cell.put("previousRubricJobId",cell.get("rubricJobId"));
                    ledger.cell=name;ledger.purpose="generation";cell.put("state","REPLACING_LOCAL_DEBATE");saveManifest(batch,manifest);
                    var session=app.start(original.settings());cell.put("runId",session.id());saveManifest(batch,manifest);
                    while(!session.isFinished()) {cell.put("speechCount",session.transcript().events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count());saveManifest(batch,manifest);Thread.sleep(500);}
                    var transcript=session.transcript();String json=Json.write(transcript);long count=transcript.events().stream().filter(e -> e.type()==PublicEvent.Type.SPEECH).count();
                    cell.put("generationOutcome",transcript.outcome().name());cell.put("speechCount",count);cell.put("startedAt",transcript.startedAt());cell.put("endedAt",transcript.endedAt());cell.put("transcriptSha256",Hashes.sha256(json));
                    AtomicFiles.write(batch.resolve("public/transcripts/"+name+".json"),json);AtomicFiles.write(batch.resolve("public/transcripts/"+name+".txt"),app.textExport(session.id()));
                    var local=app.evaluate(session.id(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance")));cell.put("localJobId",local.id());local=waitJob(app,local.id());cell.put("localJobState",local.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+name+"-local.json"),Json.write(local.result()));
                    if(local.result() instanceof engine.evaluation.local.LocalReport report) for(var method:report.methods()) for(int bi=0;bi<method.batches().size();bi++)
                        AtomicFiles.write(batch.resolve("public/raw-evaluation/"+name+"-"+method.methodId()+"-replacement-batch-"+(bi+1)+".json"),Json.write(method.batches().get(bi)));
                    ledger.purpose="rubric";var judge=app.evaluateLlm(session.id(),Map.of("modelPreset","study-judge"));cell.put("rubricJobId",judge.id());judge=waitJob(app,judge.id());cell.put("rubricJobState",judge.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+name+"-rubric.json"),Json.write(judge.result()));
                    boolean complete=transcript.outcome()==Transcript.Outcome.COMPLETE && count==25 && local.state()==BackgroundJob.State.COMPLETE && judge.state()==BackgroundJob.State.COMPLETE;
                    if(!complete) failed=true;cell.put("state",complete?"COMPLETE":"COMPLETE_WITH_FAILURES");saveManifest(batch,manifest);System.out.println("LOCAL_REPLACEMENT "+name+" "+cell.get("state"));
                }
                manifest.put("state",failed?"REPLACEMENT_FAILED":"ANALYZED");manifest.put("budgetCommittedUsd",ledger.committed());saveManifest(batch,manifest);
            }
        } finally {
            var children=runtime.descendants().toList();runtime.destroy();if(!runtime.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) runtime.destroyForcibly();
            for(var child:children) if(child.isAlive()) child.destroy();
        }
    }
    @SuppressWarnings("unchecked")
    static void recoverLocal(Path batch) throws Exception {
        var manifest=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("manifest.json")));
        if(!Set.of("COLLECTED","LOCAL_REANALYSIS_FAILED").contains(manifest.get("state"))) throw new IllegalStateException("Wait for the original collector to finish before local reanalysis");
        Path root=batch.resolve("application");
        // No generator or paid evaluator may be constructed or called during this recovery mode.
        try(var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> {throw new IllegalStateException("Model calls forbidden in local reanalysis");})) {
            boolean failed=false;
            for(var cell:(List<Map<String,Object>>)manifest.get("cells")) {
                if("COMPLETE".equals(cell.get("localJobState"))) continue;
                String id=(String)cell.get("runId"), name=(String)cell.get("cellId");
                var transcript=app.find(id).transcript();
                if(transcript.outcome()!=Transcript.Outcome.COMPLETE) throw new IllegalStateException("Only complete retained debates can receive final local reanalysis");
                Path file=batch.resolve("public/reports/"+name+"-local.json");
                if(Files.exists(file)) {
                    Path prior=batch.resolve("public/reports/"+name+"-local-attempt-"+cell.get("localJobId")+".json");
                    if(!Files.exists(prior)) Files.copy(file,prior);
                }
                var history=(List<String>)cell.computeIfAbsent("previousLocalJobIds",key -> new ArrayList<String>());
                if(cell.get("localJobId") instanceof String previous) history.add(previous);
                var job=app.evaluate(id,Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance")));
                cell.put("localJobId",job.id()); cell.put("state","REANALYZING_LOCAL"); saveManifest(batch,manifest);
                job=waitJob(app,job.id()); cell.put("localJobState",job.state().name());
                AtomicFiles.write(file,Json.write(job.result()));
                AtomicFiles.write(batch.resolve("operations/"+name+"-local-reanalysis-job.json"),Json.write(job));
                if(job.result() instanceof engine.evaluation.local.LocalReport report) {
                    for(var method:report.methods()) for(int bi=0;bi<method.batches().size();bi++)
                        AtomicFiles.write(batch.resolve("public/raw-evaluation/"+name+"-"+method.methodId()+"-reanalysis-batch-"+(bi+1)+".json"),Json.write(method.batches().get(bi)));
                }
                boolean complete=job.state()==BackgroundJob.State.COMPLETE;
                if(!complete) failed=true;
                cell.put("state",complete && "COMPLETE".equals(cell.get("rubricJobState")) ? "COMPLETE" : "COMPLETE_WITH_FAILURES");
                saveManifest(batch,manifest); System.out.println("LOCAL_REANALYSIS "+name+" "+job.state());
            }
            manifest.put("state",failed ? "LOCAL_REANALYSIS_FAILED" : "ANALYZED"); saveManifest(batch,manifest);
        }
    }
    static void saveManifest(Path batch,Map<String,Object> manifest) { AtomicFiles.write(batch.resolve("manifest.json"),Json.write(manifest)); }
    @SuppressWarnings("unchecked")
    static void checkFakePrivacy(ChatRequest request,Map<String,Object> manifest,Path root,Ledger ledger) {
        var cell=((List<Map<String,Object>>)manifest.get("cells")).stream().filter(c -> ledger.cell.equals(c.get("cellId"))).findFirst().orElseThrow();
        var members=(List<Map<String,Object>>)((Map<String,Object>)cell.get("settings")).get("members");
        long ordinal=ledger.calls.stream().filter(c -> ledger.cell.equals(c.get("cellId")) && "generation".equals(c.get("purpose"))).count()-1;
        String expected=ledger.purpose.equals("rubric")?"NONE":(String)members.get((int)(ordinal%members.size())).get("strategy");
        String system=request.systemInstructions().replace("\r\n","\n");
        String publicMessages=Json.write(request.messages()).replace("\\r\\n","\\n");
        var names=Map.of("TOPIC_DERAILMENT","TopicDerailment.txt","STRAW_MAN","StrawMan.txt","PROCEDURAL_MANIPULATION","ProceduralManipulation.txt");
        for(var tactic:names.entrySet()) {
            try {
                String prompt=Files.readString(root.resolve("prompts/strategies/"+tactic.getValue())).replace("\r\n","\n").strip();
                if(system.contains(prompt)!=expected.equals(tactic.getKey())) throw new AssertionError("Private tactic delivered to wrong recipient");
                if(publicMessages.contains(prompt) || request.messages().stream().anyMatch(m -> m.content().replace("\r\n","\n").contains(prompt))) throw new AssertionError("Private tactic leaked to public messages or blind judge");
            } catch(java.io.IOException e) { throw new IllegalStateException("Fake privacy source unavailable",e); }
        }
    }
    static void selfTest(Path directory) throws Exception {
        Files.createDirectories(directory);
        var model=new ModelConfig("openai","gpt-6-luna",.6,"none",4096,180);
        var request=new ChatRequest("Private sentinel 汉字🙂",List.of(new ChatMessage(ChatMessage.Role.USER,"Public cue")),model);
        var ledger=new Ledger(directory.resolve("ledger.json"),4.5);
        var reply=new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,10),7);
        ledger.wrap(r -> reply).complete(request);
        if(Math.abs(ledger.committed()-(100*INPUT_RATE+10*OUTPUT_RATE))>1e-12) throw new AssertionError("Usage reconciliation");
        boolean[] called={false};
        var refused=new Ledger(directory.resolve("refused.json"),0.0);
        try { refused.wrap(r -> {called[0]=true; return reply;}).complete(request); throw new AssertionError("Budget refusal missing"); }
        catch(IllegalStateException expected) { if(called[0] || !refused.calls.isEmpty()) throw new AssertionError("Paid dispatch before budget admission"); }
        var uncertain=new Ledger(directory.resolve("uncertain.json"),4.5);
        uncertain.wrap(r -> new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,null,1)).complete(request);
        if(uncertain.committed()<=ledger.committed()) throw new AssertionError("Unknown usage reservation lost");
        var failed=new Ledger(directory.resolve("failed.json"),4.5);
        try { failed.wrap(r -> {throw new IllegalStateException("Fake failure");}).complete(request); } catch(IllegalStateException expected) { }
        if(failed.committed()<=ledger.committed()) throw new AssertionError("Failed request reservation lost");
        if(Files.readString(directory.resolve("ledger.json")).contains("Private sentinel")) throw new AssertionError("Private prompt leaked to operations");
        ledger.purpose="rubric";
        var rawReply=new ChatResponse("{\"score\":0}","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,10),7);
        ledger.wrap(r -> rawReply).complete(request);
        if(!Files.readString(directory.getParent().resolve("public/raw-evaluation/self-test-judge-call-2.json")).equals(rawReply.text())) throw new AssertionError("Original judge reply changed");
        var invalid=new Ledger(directory.resolve("invalid-bound.json"),4.5);
        try { invalid.wrap(r -> new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(500000,10),1)).complete(request); throw new AssertionError("Bound violation missing"); } catch(IllegalStateException expected) { }
        try { invalid.wrap(r -> {called[0]=true; return reply;}).complete(request); throw new AssertionError("Bound violation did not stop future paid calls"); } catch(IllegalStateException expected) { if(called[0]) throw new AssertionError("Bound-violation paid dispatch"); }
        var prepaid=new Ledger(directory.resolve("prepaid.json"),null);
        prepaid.calls.add(new LinkedHashMap<>(Map.of("budgetUsd",5.0)));
        prepaid.wrap(r -> reply).complete(request);
        if(prepaid.committed()<=5 || !Files.readString(prepaid.file).contains("\"budgetLimitUsd\":null")) throw new AssertionError("Prepaid policy must retain accounting beyond the former gate");
        AtomicFiles.write(directory.resolve("result.json"),Json.write(Map.of("state","PASS","checks",List.of("usage reconciliation","pre-dispatch budget refusal","unknown usage retained","failed request retained","private prompt absent","UTF-8 input bound","stop after usage bound violation","prepaid accounting beyond former gate"),"paidCalls",0)));
        System.out.println("SELF_TEST_PASS");
    }
}
