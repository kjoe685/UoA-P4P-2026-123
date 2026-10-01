package engine.application;

import engine.ChatManager;
import engine.agent.*;
import engine.config.*;
import engine.demo.DemoChatManager;
import engine.openAi.OpenAIKeyReader;
import engine.prompt.PromptManager;
import engine.transcript.*;
import engine.utils.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Shared application operations. Neither adapter constructs agents or owns persistence. */
public final class DebateApplication implements AutoCloseable {
    private final Path root;
    private final RunStore store;
    private final Function<ModelConfig,ChatManager> providers;
    private final Map<String,RunSession> sessions=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor jobs;
    public DebateApplication(Path root,RunStore store,Function<ModelConfig,ChatManager> providers) {
        this.root=root; this.store=store; this.providers=providers;
        this.jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
                task -> { Thread thread=new Thread(task,"parliament-job"); thread.setDaemon(true); return thread; });
        for (String id:store.ids()) {
            try { sessions.put(id,RunSession.restore(id,store)); }
            catch (RuntimeException e) { System.err.println("A saved run could not be read: "+id); }
        }
    }
    public static DebateApplication local(Path root) {
        return new DebateApplication(root,new RunStore(root.resolve("runs")),model -> switch (model.provider()) {
            case "demo" -> new DemoChatManager();
            case "openai" -> new engine.provider.OpenAIChatManager(OpenAIKeyReader.read(new FileTextReader()));
            default -> throw new IllegalArgumentException("This provider is not integrated yet");
        });
    }
    public ConfigurationSnapshot configuration() { return ConfigurationSnapshot.load(root); }
    public synchronized RunSession start(Map<String,Object> body) {
        if (jobs.getActiveCount()+jobs.getQueue().size()>=6) throw new IllegalStateException("Debate job limit reached");
        var snapshot=configuration(); var spec=RunSpec.resolve(body,snapshot.config());
        var prompts=new PromptManager(snapshot);
        List<Agent> agents=new ArrayList<>(); Map<String,String> resolved=new LinkedHashMap<>();
        for (var member:spec.members()) {
            var profile=snapshot.config().parties().get(member.party());
            var identity=new Participant(member.party().name().toLowerCase(Locale.ROOT),profile.displayName()+" MP",member.party(),profile.displayName());
            var grounding=snapshot.excerpts().getExcerpts(member.party());
            String prompt=prompts.assemblePersonaPrompt(identity.name(),profile,member.strategy(),grounding);
            resolved.put(identity.id(),prompt);
            var model=snapshot.config().models().get(member.modelPreset());
            agents.add(new Agent(identity,member.strategy(),providers.apply(model),prompt,grounding,model));
        }
        var setup=new PrivateSetup(spec,snapshot.config(),snapshot.sourceContents(),snapshot.sourceHashes(),resolved);
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
    @Override public void close() {
        sessions.values().stream().filter(session -> !session.isFinished()).forEach(RunSession::adjourn);
        jobs.shutdown();
        try { if (!jobs.awaitTermination(5,TimeUnit.SECONDS)) jobs.shutdownNow(); }
        catch (InterruptedException e) { jobs.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
