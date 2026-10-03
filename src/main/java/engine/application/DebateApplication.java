package engine.application;

import engine.ChatManager;
import engine.agent.*;
import engine.config.*;
import engine.prompt.PromptManager;
import engine.transcript.*;
import engine.utils.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.BiFunction;

/** Shared application operations. Neither adapter constructs agents or owns persistence. */
public final class DebateApplication implements AutoCloseable {
    private final Path root;
    private final RunStore store;
    private final AssetService assets;
    private final CorpusService corpus;
    private final SettingsService settings;
    private final JobService background;
    private final ManagedNlp nlp;
    private final ManagedOllama ollama;
    private final LocalEvaluationService evaluation;
    private final PilotService pilots;
    private final LlmEvaluationService llmEvaluation;
    private final BiFunction<ModelConfig,OllamaConfig,ChatManager> providers;
    private final Map<String,RunSession> sessions=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor jobs;
    private boolean closed;
    public DebateApplication(Path root,RunStore store,Function<ModelConfig,ChatManager> providers) {
        this(root,store,providers,null);
    }
    public DebateApplication(Path root,RunStore store,Function<ModelConfig,ChatManager> providers,
                             Function<engine.evaluation.local.LocalEvaluationConfig,engine.evaluation.local.NlpClient> analysisClients) {
        this(root,store,(model,config) -> providers.apply(model),analysisClients,new ManagedOllama(root));
    }
    public DebateApplication(Path root,RunStore store,BiFunction<ModelConfig,OllamaConfig,ChatManager> providers,
                             Function<engine.evaluation.local.LocalEvaluationConfig,engine.evaluation.local.NlpClient> analysisClients,ManagedOllama ollama) {
        this(root,store,providers,analysisClients,ollama,new ManagedNlp(root));
    }
    DebateApplication(Path root,RunStore store,BiFunction<ModelConfig,OllamaConfig,ChatManager> providers,
                      Function<engine.evaluation.local.LocalEvaluationConfig,engine.evaluation.local.NlpClient> analysisClients,ManagedOllama ollama,ManagedNlp nlp) {
        this.root=root; this.store=store; this.providers=providers;
        this.ollama=ollama;
        this.assets=new AssetService(root); this.settings=new SettingsService(root.resolve("runs/settings"));
        this.corpus=new CorpusService(assets);
        this.background=new JobService(root.resolve("runs/jobs")); this.nlp=nlp;
        this.llmEvaluation=new LlmEvaluationService(root,background,providers);
        java.util.function.BiFunction<engine.evaluation.local.LocalEvaluationConfig,String,engine.evaluation.local.NlpClient> analysisFactory=(config,settings) -> {
            if (analysisClients!=null) return analysisClients.apply(config);
            nlp.ensureRunning(config,settings); return new engine.evaluation.local.HttpNlpClient(config.endpoint(),java.time.Duration.ofSeconds(config.timeoutSeconds()));
        };
        this.evaluation=new LocalEvaluationService(root,background,analysisFactory);
        this.pilots=new PilotService(root,background,evaluation,analysisFactory);
        this.jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
                task -> { Thread thread=new Thread(task,"parliament-job"); thread.setDaemon(true); return thread; });
        for (String id:store.ids()) {
            try { sessions.put(id,RunSession.restore(id,store)); }
            catch (RuntimeException e) { System.err.println("A saved run could not be read: "+id); }
        }
    }
    public static DebateApplication local(Path root) {
        var ollama=new ManagedOllama(root); var factory=new engine.provider.ProviderFactory(root,ollama);
        return new DebateApplication(root,new RunStore(root.resolve("runs")),factory::forModel,null,ollama);
    }
    public ConfigurationSnapshot configuration() { return ConfigurationSnapshot.load(root); }
    public AssetService assets() { return assets; }
    public CorpusService corpus() { return corpus; }
    public PilotService pilots() { return pilots; }
    public SettingsService settings() { return settings; }
    public JobService background() { return background; }
    public Map<String,Object> ollamaReadiness() { return ollama.readiness(OllamaConfig.load(root)); }
    public BackgroundJob setupOllama() {
        var config=OllamaConfig.load(root);
        return background.submit("ollama-setup",null,Map.of("localRuntime",config),context -> {
            ollama.install(config,context); context.checkCancelled(); context.update("Starting managed local LLM service",null);
            ollama.ensureRunning(config); context.update("Local runtime responds; model downloads remain explicit",ollama.readiness(config)); return true;
        });
    }
    public BackgroundJob downloadOllamaModel(String preset) {
        var config=OllamaConfig.load(root); EngineConfig settings;
        try { settings=Json.read(java.nio.file.Files.readString(root.resolve("config/engine.json")),EngineConfig.class); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read model presets"); }
        var model=settings.models().get(preset);
        if (model==null || !model.provider().equals("ollama")) throw new IllegalArgumentException("Choose an Ollama model preset");
        OllamaConfig.requireLocalModel(model.model());
        return background.submit("ollama-model-setup",null,Map.of("modelPreset",preset,"model",model,"localRuntime",config),context -> {
            ollama.download(config,model.model(),context); return true;
        });
    }
    public Map<String,Object> localReadiness() { return nlp.readiness(evaluation.configuration()); }
    public BackgroundJob setupLocalNlp() {
        var config=evaluation.configuration(); String captured=localModelSettings();
        return background.submit("local-setup",null,Map.of("method","vader-sentiment","configuration",config,"localModelSettings",captured),context -> {
            context.update("Installing managed Python and locked VADER dependencies",null); nlp.install(context);
            context.checkCancelled(); context.update("Starting local analysis service",null); nlp.ensureRunning(config,captured);
            context.update("VADER dependencies installed; analysis remains explicit",nlp.readiness(config,captured)); return true;
        });
    }
    public BackgroundJob setupLocalModel(String method) {
        if (!Set.of("cardiff-sentiment","deberta-stance").contains(method)) throw new IllegalArgumentException("Choose a local transformer method");
        String settings=localModelSettings();
        var config=evaluation.configuration();
        return background.submit("model-setup",null,Map.of("method",method,"configuration",config,"localModelSettings",settings),context -> {
            nlp.download(context,method,settings); context.checkCancelled();
            context.update("Starting local service after model setup",null); nlp.ensureRunning(config,settings);
            context.update("Pinned files cached; models load only for explicit analysis",nlp.readiness(config,settings)); return true;
        });
    }
    private String localModelSettings() {
        try { return java.nio.file.Files.readString(root.resolve("nlp/config/models.json")); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read local model settings"); }
    }
    public BackgroundJob evaluate(String id,Map<String,Object> body) { return evaluation.start(find(id).transcript(),body); }
    public BackgroundJob evaluateLlm(String id,Map<String,Object> body) {
        if (!Set.of("modelPreset").containsAll(body.keySet())) throw new IllegalArgumentException("Unknown LLM evaluation setting");
        var session=find(id); String preset=store.setup(id).settings().evaluatorModelPreset();
        if (body.containsKey("modelPreset")) {
            if (!(body.get("modelPreset") instanceof String value)) throw new IllegalArgumentException("Choose an evaluator model preset");
            preset=value;
        }
        EngineConfig current;
        try { current=Json.read(java.nio.file.Files.readString(root.resolve("config/engine.json")),EngineConfig.class); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read model presets"); }
        var model=current.models().get(preset);
        if (model==null) throw new IllegalArgumentException("Unknown evaluator model preset");
        return llmEvaluation.start(session.transcript(),model,OllamaConfig.load(root));
    }
    public synchronized RunSession start(Map<String,Object> body) {
        requireOpen();
        if (jobs.getActiveCount()+jobs.getQueue().size()>=6) throw new IllegalStateException("Debate job limit reached");
        var snapshot=configuration(); var spec=RunSpec.resolve(settings.resolve(body),snapshot.config());
        var prompts=new PromptManager(snapshot);
        Map<String,Object> selectedGrounding=new LinkedHashMap<>();
        // Validate every quantity before constructing providers or scheduling generation.
        for (var member:spec.members()) selectedGrounding.put(member.party().name(),snapshot.excerpts().select(member.party(),member.groundingCount()==null ? spec.groundingCount() : member.groundingCount()));
        List<Agent> agents=new ArrayList<>(); Map<String,String> resolved=new LinkedHashMap<>();
        for (var member:spec.members()) {
            var profile=snapshot.config().parties().get(member.party());
            var identity=new Participant(member.party().name().toLowerCase(Locale.ROOT),profile.displayName()+" MP",member.party(),profile.displayName());
            @SuppressWarnings("unchecked") var chosen=(List<Map<String,Object>>)selectedGrounding.get(member.party().name());
            var grounding=chosen.stream().map(entry -> (String)entry.get("text")).toList();
            String prompt=prompts.assemblePersonaPrompt(identity.name(),profile,member.strategy(),grounding);
            resolved.put(identity.id(),prompt);
            var model=snapshot.config().models().get(member.modelPreset());
            agents.add(new Agent(identity,member.strategy(),providers.apply(model,snapshot.ollama()),prompt,grounding,model));
        }
        Map<String,String> contents=new LinkedHashMap<>(snapshot.sourceContents()), hashes=new LinkedHashMap<>(snapshot.sourceHashes());
        String selection=Json.writeCanonical(Map.of("policy","first-N-in-corpus-order-v1","corpusSha256",snapshot.sourceHashes().get("data/hansard/excerpts.json"),
                "corpusProvenance",snapshot.excerpts().provenance(),"parties",selectedGrounding));
        contents.put("selection/hansard.json",selection); hashes.put("selection/hansard.json",Hashes.sha256(selection));
        var setup=new PrivateSetup(spec,snapshot.config(),contents,hashes,resolved);
        var session=new RunSession(UUID.randomUUID().toString(),agents,snapshot,setup,store);
        sessions.put(session.id(),session);
        try { jobs.execute(session::run); }
        catch (RejectedExecutionException e) { session.adjourn(); session.run(); throw new IllegalStateException("Debate job limit reached"); }
        return session;
    }
    public RunSession find(String id) {
        RunSession session=sessions.get(id);
        if (session==null) throw new NoSuchElementException("No saved sitting with that id");
        return session;
    }
    public List<Transcript> runs() {
        return sessions.values().stream().map(RunSession::transcript)
                .sorted(Comparator.comparingLong(Transcript::startedAt).reversed()).toList();
    }
    public void ruling(String id,String text) { find(id).addSpeakerRuling(text); }
    public void cancel(String id) { find(id).adjourn(); }
    public synchronized RunSession importTranscript(Transcript source) {
        requireOpen();
        var snapshot=configuration();
        var spec=new RunSpec(source.topics(),1,source.roster().stream()
                .map(member -> new RunSpec.Member(member.party(),AdversarialStrategy.NONE,snapshot.config().agentModelPreset())).toList(),
                snapshot.config().agentModelPreset(),snapshot.config().evaluatorModelPreset());
        var setup=new PrivateSetup(spec,snapshot.config(),Map.of(),Map.of(),Map.of());
        var imported=new Transcript(2,UUID.randomUUID().toString(),source.startedAt(),
                source.endedAt()==null ? Math.max(source.startedAt(),System.currentTimeMillis()) : source.endedAt(),
                source.roster(),source.topics(),source.events(),source.outcome()==Transcript.Outcome.RUNNING ? Transcript.Outcome.INTERRUPTED : source.outcome());
        var session=RunSession.imported(imported,setup,store); sessions.put(session.id(),session); return session;
    }
    public String textExport(String id) {
        var run=find(id).transcript(); StringBuilder text=new StringBuilder("VIRTUAL PARLIAMENT: SIMULATED HANSARD\n");
        text.append("Run ").append(run.runId()).append("; outcome ").append(run.outcome()).append("\n");
        for (var member:run.roster()) text.append(member.name()).append(" (").append(member.partyName()).append(")\n");
        for (var event:run.events()) text.append("\n[").append(event.id()).append("] ")
                .append(event.speaker()==null ? "The Speaker" : event.speaker().name()).append(": ").append(event.text()).append("\n");
        return text.toString();
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Application backend is closed"); }
    @Override public void close() {
        // Use the same admission lock as start/import, then release it before waiting for workers.
        synchronized (this) { if (closed) return; closed=true; }
        // Signal generation before a background save or runtime cleanup can delay shutdown.
        try { sessions.values().stream().filter(session -> !session.isFinished()).forEach(RunSession::adjourn); }
        finally {
            jobs.shutdown();
            try { background.close(); }
            finally {
                try { nlp.close(); }
                finally {
                    try {
                        try { if (!jobs.awaitTermination(5,TimeUnit.SECONDS)) jobs.shutdownNow(); }
                        catch (InterruptedException e) { jobs.shutdownNow(); Thread.currentThread().interrupt(); }
                    } finally { ollama.close(); }
                }
            }
        }
    }
}
