package engine.application;

import engine.TestFixtures;
import engine.chat.*;
import engine.config.*;
import engine.evaluation.EvaluationStatus;
import engine.evaluation.llm.LlmReport;
import engine.evaluation.llm.LlmEvaluationResources;
import engine.provider.ProviderFactory;
import engine.provider.ChatCompletionsProvider;
import engine.transcript.*;
import engine.utils.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit real selected-model acceptance; never a JUnit test or research validation. */
public final class OfficialOllamaModelSmoke {
    private OfficialOllamaModelSmoke() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) throw new IllegalArgumentException("Use an isolated fixture root, port, stop signal, context limit and optional public transcript");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path stopSignal = Path.of(args[2]).toAbsolutePath().normalize();
        String profile = System.getenv("USERPROFILE");
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") || !root.startsWith(target)
                || root.equals(target) || profile == null || !Path.of(profile).toAbsolutePath().normalize().equals(root.resolve("profile"))
                || !root.equals(stopSignal.getParent())) throw new IllegalArgumentException("Use the isolated Windows runner model mode");
        int port = Integer.parseInt(args[1]);
        if (port < 1024 || port > 65535 || port == 11434 || occupied(port)) throw new IllegalArgumentException("Choose a free non-default test port");
        boolean behaviour=args.length==5 && args[4].equals("--behaviour");
        Path sourcePath = args.length == 5 && !behaviour ? Path.of(args[4]).toRealPath() : null;
        require(!behaviour || Files.isDirectory(root.resolve(".runtime/ollama-models")),"Behaviour preparation requires an existing isolated model cache");
        Transcript source = null; String sourceHash = null;
        if (sourcePath != null) {
            require(sourcePath.startsWith(target.toRealPath()) && !sourcePath.equals(root.resolve("public-transcript.json"))
                    && Files.isRegularFile(sourcePath) && Files.size(sourcePath) <= 25_000_000,
                    "Use retained public evidence under target, separate from helper output");
            byte[] bytes = Files.readAllBytes(sourcePath); sourceHash = LlmEvaluationResources.sha256(bytes);
            source = Json.read(Utf8.decode(bytes), Transcript.class);
            require(source.outcome() == Transcript.Outcome.COMPLETE && source.roster().size() == 2 && source.topics().size() == 1
                    && Set.of(2L,4L).contains(source.events().stream().filter(event -> event.type() == PublicEvent.Type.SPEECH).count()),
                    "Retained acceptance evidence must be a completed bounded two-or-four-speech single-topic sitting");
            require(Files.isDirectory(root.resolve(".runtime/ollama-models")), "Evidence mode requires an existing local model cache");
        }
        if (!Files.isDirectory(root.resolve("config"))) TestFixtures.copyResources(root);
        var initial = Json.read(Files.readString(root.resolve("config/engine.json")), EngineConfig.class);
        // Bound ordinary contributions and preserve the configured model preset.
        var bounded = new EngineConfig(initial.schemaVersion(), initial.defaultRounds(), initial.defaultTopic(),
                initial.agentModelPreset(), initial.evaluatorModelPreset(), initial.models(), initial.parties(), new InterruptionConfig(0,0,123));
        AtomicFiles.write(root.resolve("config/engine.json"), Json.write(bounded));
        var config = new OllamaConfig(1, URI.create("http://127.0.0.1:" + port), Integer.parseInt(args[3]), 60, 3600);
        AtomicFiles.write(root.resolve("config/ollama.json"), Json.write(config));
        String acceptanceId = UUID.randomUUID().toString();
        if (sourcePath != null) {
            // Explicit evidence mode captures current editable evaluator assets; old jobs retain their own snapshots.
            var current = LlmEvaluationResources.load(Path.of(""));
            for (var entry : current.sourceHashes().entrySet()) {
                byte[] bytes = Files.readAllBytes(Path.of(entry.getKey()));
                require(entry.getValue().equals(LlmEvaluationResources.sha256(bytes)), "Evaluator resources changed while capturing acceptance");
                AtomicFiles.write(root.resolve(entry.getKey()), Utf8.decode(bytes));
            }
        }
        Path diagnostics = root.resolve("diagnostics").resolve(acceptanceId);
        Path log = root.resolve(".runtime/ollama-service-" + port + ".log");
        long initialChatRequests = 0;
        var runtime = new ManagedOllama(root); var factory = new ProviderFactory(root, runtime);
        AtomicInteger modelCalls = new AtomicInteger();
        var application = new DebateApplication(root, new RunStore(root.resolve("runs")), (model, capturedRuntime) -> {
            require(model.provider().equals("ollama"), "Genuine acceptance must never select a cloud or demo provider");
            var adapter = factory.forModel(model, capturedRuntime);
            return request -> {
                int attempt = modelCalls.incrementAndGet();
                var messages = ChatCompletionsProvider.messages(request, true);
                long estimated = Json.write(messages).getBytes(StandardCharsets.UTF_8).length + 256L + messages.size()*32L + model.maxCompletionTokens();
                var diagnostic = new LinkedHashMap<String,Object>();
                diagnostic.put("scope", request.outputSchema() == null ? "speech" : "blind-rubric");
                diagnostic.put("conservativeRequestOutputEstimate", estimated); diagnostic.put("contextTokens", capturedRuntime.contextTokens());
                diagnostic.put("requestSha256", Hashes.sha256(Json.write(request)));
                Path record = diagnostics.resolve("attempt-" + attempt + ".json");
                AtomicFiles.write(record, Json.write(diagnostic));
                try {
                    var response = adapter.complete(request);
                    if (request.outputSchema() != null) AtomicFiles.write(diagnostics.resolve("rubric-response-" + attempt + ".json"), Json.write(response));
                    return response;
                } catch (RuntimeException error) {
                    diagnostic.put("failure", error instanceof ContextBudgetExceededException ? "context_budget" : "provider_error");
                    AtomicFiles.write(record, Json.write(diagnostic)); throw error;
                }
            };
        }, null, runtime);
        Thread cleanup = new Thread(application::close, "selected-model-acceptance-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        var result = new LinkedHashMap<String,Object>(); result.put("state", "FAILED");
        result.put("acceptanceId", acceptanceId); result.put("diagnostics", root.relativize(diagnostics).toString());
        result.put("modelPreset", "qwen3-local"); result.put("model", bounded.models().get("qwen3-local").model());
        result.put("contextTokens", config.contextTokens());
        result.put("scope", behaviour ? "four-speech-public-human-review-preparation" : sourcePath == null ? "new-sitting-and-blind-rubric" : "retained-public-evidence-blind-rubric");
        if (sourcePath != null) result.put("sourceSha256", sourceHash);
        try {
            require(OfficialOllamaSmoke.finish(application, application.setupOllama().id(), stopSignal).state() == BackgroundJob.State.COMPLETE,
                    "Cached official runtime setup must complete");
            // Owned startup replaces its operational log; measure this invocation after that replacement.
            initialChatRequests = Files.exists(log) ? chatCount(log) : 0;
            if (sourcePath == null && !behaviour) {
                System.out.println("Deliberately downloading only the configured local qwen3:8b preset");
                var download = OfficialOllamaSmoke.finish(application, application.downloadOllamaModel("qwen3-local").id(), stopSignal);
                AtomicFiles.write(root.resolve("model-download.json"), Json.write(download));
                require(download.state() == BackgroundJob.State.COMPLETE, "Selected model setup failed; retain the cache and job");
                require(OfficialOllamaSmoke.finish(application, application.downloadOllamaModel("qwen3-local").id(), stopSignal).state() == BackgroundJob.State.COMPLETE,
                        "Explicit repeat model setup must reuse the cache");
            } else {
                runtime.requireCachedModel(config, bounded.models().get("qwen3-local").model());
                result.put("modelDownloadSubmitted", false);
            }
            var inventory = application.ollamaReadiness();
            result.put("observedInventory", Json.parse(Json.write(inventory.get("models"))));
            result.put("modelCacheReuse", true);
            RunSession sitting;
            if (sourcePath != null) {
                System.out.println("Importing retained public evidence; no new speeches or model download");
                sitting = application.importTranscript(source);
                var imported = sitting.transcript();
                require(imported.events().equals(source.events()) && imported.roster().equals(source.roster()) && imported.topics().equals(source.topics())
                        && imported.startedAt() == source.startedAt() && imported.endedAt().equals(source.endedAt()), "Imported public evidence must stay exact");
            } else {
                System.out.println("Generating a bounded genuine local sitting: two members, "+(behaviour ? "two rounds" : "one round")+", zero grounding");
                sitting = application.start(Map.of("topics", List.of("Should New Zealand build more public housing?"), "rounds", behaviour ? 2 : 1,
                        "members", List.of(Map.of("party", "LABOUR"), Map.of("party", "NATIONAL")), "groundingCount", 0,
                        "agentModelPreset", "qwen3-local", "evaluatorModelPreset", "qwen3-local"));
            }
            result.put("runId", sitting.id());
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(12);
            while (!sitting.isFinished()) {
                if (Files.exists(stopSignal) || System.nanoTime() > deadline) {
                    application.cancel(sitting.id()); throw new IllegalStateException("Local sitting stopped; committed evidence retained");
                }
                Thread.sleep(250);
            }
            Transcript evidence = sitting.transcript();
            AtomicFiles.write(root.resolve("public-transcript.json"), Json.write(evidence));
            AtomicFiles.write(root.resolve("public-transcript-" + acceptanceId + ".json"), Json.write(evidence));
            AtomicFiles.write(root.resolve("public-transcript.txt"), application.textExport(sitting.id()));
            AtomicFiles.write(root.resolve("public-transcript-"+acceptanceId+".txt"), application.textExport(sitting.id()));
            result.put("sittingOutcome", evidence.outcome());
            result.put("publicContributions", evidence.events().stream().filter(event -> event.type() == PublicEvent.Type.SPEECH).count());
            long expectedContributions=source==null ? (behaviour ? 4L : 2L) : source.events().stream().filter(event -> event.type()==PublicEvent.Type.SPEECH).count();
            require(evidence.outcome() == Transcript.Outcome.COMPLETE && result.get("publicContributions").equals(expectedContributions),
                    "Actual local sitting must complete its bounded contributions; inspect retained public evidence");
            if (behaviour) {
                require(modelCalls.get()==4 && chatCount(log)-initialChatRequests==4,"Behaviour preparation must make exactly four speech calls");
                prepareHumanReview(root,acceptanceId,evidence);
                result.put("publicTranscriptSha256",Hashes.sha256(Json.write(evidence))); result.put("humanJudgments","blank");
                result.put("rubricJobSubmitted",false); application.close();
                long stopped=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (occupied(port) && System.nanoTime()<stopped) Thread.sleep(50);
                try (var reopened=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> { throw new AssertionError("Review recovery must not generate"); })) {
                    require(evidence.equals(reopened.find(sitting.id()).transcript()),"Genuine public review evidence must reopen unchanged");
                    require(!Boolean.TRUE.equals(reopened.ollamaReadiness().get("running")),"Review recovery must not start inference");
                }
                result.put("publicEvidenceRecovery",true); result.put("state","PASS");
            } else {
            long chatBefore = chatCount(log);
            boolean rejected = false;
            try {
                factory.forModel(bounded.models().get("qwen3-local"), config).complete(new ChatRequest("Bounded local context acceptance",
                        List.of(new ChatMessage(ChatMessage.Role.USER, "x".repeat(config.contextTokens()))), bounded.models().get("qwen3-local")));
            } catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("context budget"); }
            require(rejected && chatCount(log) == chatBefore, "Oversized context must be refused before real inference");
            result.put("oversizedContextRefusedBeforeInference", true);
            System.out.println("Evaluating saved public evidence with the independently selected local blind rubric");
            var evaluation = OfficialOllamaSmoke.finish(application, application.evaluateLlm(sitting.id(), Map.of("modelPreset", "qwen3-local")).id(), stopSignal);
            AtomicFiles.write(root.resolve("llm-job.json"), Json.write(evaluation));
            var report = Json.read(Json.write(evaluation.result()), LlmReport.class);
            AtomicFiles.write(root.resolve("llm-report.json"), Json.write(report));
            AtomicFiles.write(root.resolve("llm-report-" + acceptanceId + ".json"), Json.write(report));
            result.put("rubricJobState", evaluation.state()); result.put("rubricJobId", evaluation.id());
            if (!report.assessments().topics().isEmpty()) {
                result.put("rubricTopicStatus", report.assessments().topics().get(0).status());
                result.put("rubricError", report.assessments().topics().get(0).error());
            }
            require(evaluation.state() == BackgroundJob.State.COMPLETE && report.assessments().topics().size() == 1
                    && report.assessments().topics().get(0).status() == EvaluationStatus.OK, "Actual blind rubric did not validate; retain the bounded failure/report");
            var participants = report.assessments().topics().get(0).assessment().participants();
            require(participants.size() == 2 && participants.stream().allMatch(participant -> participant.metrics().size() == 5), "Rubric must retain five separate metrics per participant");
            result.put("separateRubricMetrics", 5); result.put("state", "PASS");
            }
        } finally {
            application.close(); Runtime.getRuntime().removeShutdownHook(cleanup);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (occupied(port) && System.nanoTime() < deadline) Thread.sleep(50);
            result.put("ownedPortReleased", !occupied(port));
            result.put("providerAttempts", modelCalls.get());
            if (Files.exists(log)) result.put("realChatHttpRequests", chatCount(log) - initialChatRequests);
            result.put("researchValidity", "not assessed; requires genuine human review");
            if (sourcePath != null) {
                boolean unchanged = sourceHash.equals(LlmEvaluationResources.sha256(Files.readAllBytes(sourcePath)));
                result.put("sourceUnchanged", unchanged);
                if (!unchanged) result.put("state", "FAILED");
            }
            if (!Boolean.TRUE.equals(result.get("ownedPortReleased"))) result.put("state", "FAILED");
            result.put("verifiedAt", java.time.Instant.now().toString());
            AtomicFiles.write(root.resolve("model-result.json"), Json.write(result));
            AtomicFiles.write(root.resolve("model-result-" + acceptanceId + ".json"), Json.write(result));
        }
        require(Boolean.TRUE.equals(result.get("ownedPortReleased")), "Selected-model acceptance must release its owned runtime");
        require(sourcePath == null || Boolean.TRUE.equals(result.get("sourceUnchanged")), "Acceptance source identity must remain unchanged");
        System.out.println("Genuine selected local-model acceptance PASS for " + result.get("scope") + "; no research accuracy claim");
        System.out.println("Result: " + root.resolve("model-result.json"));
    }
    private static long chatCount(Path log) throws Exception {
        try (var lines = Files.lines(log)) { return lines.filter(line -> line.contains("/api/chat") && line.contains("POST")).count(); }
    }
    private static void prepareHumanReview(Path root,String id,Transcript evidence) throws Exception {
        var previous=new ArrayList<PublicEvent>(); var rows=new ArrayList<Map<String,Object>>();
        for (var event:evidence.events()) {
            if (event.speaker()==null || event.type()!=PublicEvent.Type.SPEECH) continue;
            var own=previous.stream().filter(turn -> turn.speaker().id().equals(event.speaker().id())).toList();
            var other=previous.stream().filter(turn -> turn.speaker().party()!=event.speaker().party()).toList();
            var row=new LinkedHashMap<String,Object>(); row.put("turnId",event.id()); row.put("participantId",event.speaker().id());
            row.put("latestOtherPartyTurnId",other.isEmpty() ? null : other.get(other.size()-1).id());
            row.put("previousOwnTurnIds",own.stream().map(PublicEvent::id).toList());
            for (String field:List.of("specificClaimResponse","positionContinuity","reasonedConcession","reviewer","reviewNotes")) row.put(field,null);
            rows.add(row); previous.add(event);
        }
        AtomicFiles.write(root.resolve("human-review-"+id+".json"),Json.write(Map.of("schemaVersion",1,"runId",evidence.runId(),
                "publicTranscriptSha256",Hashes.sha256(Json.write(evidence)),"scope","public pointers for human review; no judgments or private setup","turns",rows)));
    }
    private static boolean occupied(int port) {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 250); return true; }
        catch (java.io.IOException e) { return false; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
