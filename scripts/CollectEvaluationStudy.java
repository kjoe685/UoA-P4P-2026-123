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
        final double limit;
        final List<Map<String,Object>> calls = new ArrayList<>();
        boolean boundViolated;
        volatile String cell = "self-test", purpose = "generation";
        Ledger(Path file, double limit) { this.file=file; this.limit=limit; }
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
                    if (committed()+reservation>limit) throw new IllegalStateException("Study USD4.50 budget admission refused before dispatch");
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
        synchronized void save() { AtomicFiles.write(file,Json.write(Map.of("schemaVersion",1,"budgetLimitUsd",limit,"budgetCommittedUsd",committed(),"calls",calls))); }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length==2 && args[0].equals("self-test")) { selfTest(Path.of(args[1])); return; }
        if (args.length!=3 || !Set.of("collect","self-test-matrix").contains(args[0])) throw new IllegalArgumentException("Use collect or self-test-matrix REPOSITORY BATCH, or self-test OUTPUT");
        boolean fake=args[0].equals("self-test-matrix");
        Path repo=Path.of(args[1]).toAbsolutePath(), batch=Path.of(args[2]).toAbsolutePath(), root=batch.resolve("application");
        var manifest=(Map<String,Object>)Json.parse(Files.readString(batch.resolve("manifest.json")));
        if (!manifest.get("state").equals("PREPARED") || Files.exists(batch.resolve("operations/usage.json")))
            throw new IllegalStateException("Already-started batch; inspect retained evidence, never restart inference automatically");
        for (var e:((Map<String,String>)manifest.get("frozenSourceHashes")).entrySet())
            if (!Hashes.sha256(Files.readAllBytes(root.resolve(e.getKey()))).equals(e.getValue())) throw new IllegalStateException("Frozen study source changed");
        var ledger=new Ledger(batch.resolve("operations/usage.json"),((Number)manifest.get("budgetLimitUsd")).doubleValue());
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
                ? request -> { try { Thread.sleep(100); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fake interrupted"); }
                    return new ChatResponse("Mr Speaker, this is a synthetic harness speech about the topic. This text verifies scheduling only.",model.provider(),model.model(),ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,25),100); }
                : factory.forModel(model,config));
            try(var app=new DebateApplication(root,new RunStore(root.resolve("runs")),providers,null,managed)) {
                manifest.put("state","RUNNING"); manifest.put("startedAt",System.currentTimeMillis()); saveManifest(batch,manifest);
                for (var cell:(List<Map<String,Object>>)manifest.get("cells")) {
                    ledger.cell=(String)cell.get("cellId"); ledger.purpose="generation";
                    System.out.println("START_CELL "+ledger.cell);
                    cell.put("state","GENERATING"); saveManifest(batch,manifest);
                    var session=app.start((Map<String,Object>)cell.get("settings"));
                    cell.put("runId",session.id()); saveManifest(batch,manifest);
                    long displayed=-1;
                    while (!session.isFinished()) {
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
                    if (fake) {
                        if (transcript.outcome()!=Transcript.Outcome.COMPLETE || speeches!=25 || transcript.roster().size()!=5 || transcript.events().stream().anyMatch(e -> e.type()==PublicEvent.Type.INTERJECTION)) throw new AssertionError("Matrix count/ordinary speaking failure");
                        cell.put("state","SYNTHETIC_COMPLETE"); saveManifest(batch,manifest); continue;
                    }
                    cell.put("state","EVALUATING_LOCAL"); saveManifest(batch,manifest);
                    var local=app.evaluate(session.id(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance")));
                    cell.put("localJobId",local.id()); saveManifest(batch,manifest);
                    local=waitJob(app,local.id());
                    cell.put("localJobState",local.state().name());
                    AtomicFiles.write(batch.resolve("public/reports/"+ledger.cell+"-local.json"),Json.write(local.result()));
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
        var process=builder.start();
        AtomicFiles.write(batch.resolve("operations/runtime-process.json"),Json.write(Map.of("pid",process.pid(),"executable",manifest.get("ollamaExecutable"),"port",11451)));
        return process;
    }
    static BackgroundJob waitJob(DebateApplication app,String id) throws Exception { while(!app.background().find(id).terminal()) Thread.sleep(500); return app.background().find(id); }
    static void saveManifest(Path batch,Map<String,Object> manifest) { AtomicFiles.write(batch.resolve("manifest.json"),Json.write(manifest)); }
    static void selfTest(Path directory) throws Exception {
        Files.createDirectories(directory);
        var model=new ModelConfig("openai","gpt-6-luna",.6,"none",4096,180);
        var request=new ChatRequest("Private sentinel 汉字🙂",List.of(new ChatMessage(ChatMessage.Role.USER,"Public cue")),model);
        var ledger=new Ledger(directory.resolve("ledger.json"),4.5);
        var reply=new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(100,10),7);
        ledger.wrap(r -> reply).complete(request);
        if(Math.abs(ledger.committed()-(100*INPUT_RATE+10*OUTPUT_RATE))>1e-12) throw new AssertionError("Usage reconciliation");
        boolean[] called={false};
        var refused=new Ledger(directory.resolve("refused.json"),0);
        try { refused.wrap(r -> {called[0]=true; return reply;}).complete(request); throw new AssertionError("Budget refusal missing"); }
        catch(IllegalStateException expected) { if(called[0] || !refused.calls.isEmpty()) throw new AssertionError("Paid dispatch before budget admission"); }
        var uncertain=new Ledger(directory.resolve("uncertain.json"),4.5);
        uncertain.wrap(r -> new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,null,1)).complete(request);
        if(uncertain.committed()<=ledger.committed()) throw new AssertionError("Unknown usage reservation lost");
        var failed=new Ledger(directory.resolve("failed.json"),4.5);
        try { failed.wrap(r -> {throw new IllegalStateException("Fake failure");}).complete(request); } catch(IllegalStateException expected) { }
        if(failed.committed()<=ledger.committed()) throw new AssertionError("Failed request reservation lost");
        if(Files.readString(directory.resolve("ledger.json")).contains("Private sentinel")) throw new AssertionError("Private prompt leaked to operations");
        var invalid=new Ledger(directory.resolve("invalid-bound.json"),4.5);
        try { invalid.wrap(r -> new ChatResponse("Speech","openai","gpt-6-luna",ChatResponse.CompletionStatus.COMPLETED,new ChatResponse.TokenUsage(500000,10),1)).complete(request); throw new AssertionError("Bound violation missing"); } catch(IllegalStateException expected) { }
        try { invalid.wrap(r -> {called[0]=true; return reply;}).complete(request); throw new AssertionError("Bound violation did not stop future paid calls"); } catch(IllegalStateException expected) { if(called[0]) throw new AssertionError("Bound-violation paid dispatch"); }
        AtomicFiles.write(directory.resolve("result.json"),Json.write(Map.of("state","PASS","checks",List.of("usage reconciliation","pre-dispatch budget refusal","unknown usage retained","failed request retained","private prompt absent","UTF-8 input bound","stop after usage bound violation"),"paidCalls",0)));
        System.out.println("SELF_TEST_PASS");
    }
}
