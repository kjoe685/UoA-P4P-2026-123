package engine.application;

import engine.agent.Agent;
import engine.config.*;
import engine.debate.DebateManager;
import engine.io.EngineOutput;
import engine.prompt.PromptManager;
import engine.transcript.*;
import engine.utils.Json;
import java.util.*;

/** Durable sitting independent of the HTTP and terminal adapters. */
public final class RunSession implements EngineOutput {
    private final String id;
    private final RunStore store;
    private final PrivateSetup setup;
    private final List<Participant> roster;
    private final List<Topic> topics;
    private final List<PublicEvent> evidence=new ArrayList<>();
    private final List<String> view=new ArrayList<>();
    private final long startedAt;
    private Long endedAt;
    private Transcript.Outcome outcome;
    private DebateManager manager;
    private volatile Thread worker;
    private boolean finished;

    public RunSession(String id,List<Agent> agents,ConfigurationSnapshot snapshot,PrivateSetup setup,RunStore store) {
        this.id=id; this.store=store; this.setup=setup;
        this.roster=agents.stream().map(Agent::identity).toList(); this.topics=setup.settings().topics();
        this.startedAt=System.currentTimeMillis(); this.outcome=Transcript.Outcome.RUNNING;
        this.manager=new DebateManager(agents,topics,setup.settings().rounds(),this,new PromptManager(snapshot),snapshot.config().interruptions());
        store.create(id,setup); store.saveTranscript(transcript());
        record(sittingEvent());
    }
    private RunSession(Transcript transcript,PrivateSetup setup,List<String> view,RunStore store) {
        id=transcript.runId(); this.store=store; this.setup=setup; roster=transcript.roster(); topics=transcript.topics();
        startedAt=transcript.startedAt(); endedAt=transcript.endedAt(); outcome=transcript.outcome();
        evidence.addAll(transcript.events()); this.view.addAll(view);
        finished=outcome!=Transcript.Outcome.RUNNING;
        Set<String> displayed=new HashSet<>();
        boolean terminal=false;
        for (String json:view) {
            Object parsed=Json.parse(json);
            if (parsed instanceof Map<?,?> event) {
                if (event.get("turnId") instanceof String turn) displayed.add(turn);
                if ("adjourned".equals(event.get("type"))) terminal=true;
            }
        }
        // Recover evidence committed immediately before a crash, preserving earlier SSE ids.
        for (var event:evidence) if (!displayed.contains(event.id())) record(eventView(event,topics));
        if (!finished) finish(Transcript.Outcome.INTERRUPTED);
        else if (!terminal) record(Map.of("type","adjourned","outcome",wireOutcome(outcome),"at",endedAt));
    }
    public static RunSession restore(String id,RunStore store) {
        Transcript transcript=store.transcript(id);
        PrivateSetup setup=store.setup(id);
        List<String> view;
        try { view=store.view(id); }
        catch (IllegalStateException | IllegalArgumentException e) { view=rebuildView(transcript,setup); }
        return new RunSession(transcript,setup,view,store);
    }
    public static RunSession imported(Transcript transcript,PrivateSetup setup,RunStore store) {
        store.create(transcript.runId(),setup); store.saveTranscript(transcript);
        var view=rebuildView(transcript,setup); store.saveView(transcript.runId(),view);
        return new RunSession(transcript,setup,view,store);
    }
    public void run() {
        worker=Thread.currentThread();
        Transcript.Outcome result=Transcript.Outcome.COMPLETE;
        try {
            manager.run();
            if (manager.isStopRequested()) result=Transcript.Outcome.ADJOURNED;
        } catch (RuntimeException e) {
            if (manager.isStopRequested()) result=Transcript.Outcome.ADJOURNED;
            else {
                result=Transcript.Outcome.ERROR;
                try { record(Map.of("type","error","message","Generation failed. Check provider configuration and retry.")); }
                catch (RuntimeException ignored) { }
            }
        } finally {
            // Clear cancellation interrupt before persistence and release scheduler workers safely.
            Thread.interrupted();
            try { finish(result); }
            catch (RuntimeException e) {
                synchronized (this) { outcome=Transcript.Outcome.ERROR; endedAt=System.currentTimeMillis(); finished=true; notifyAll(); }
            }
        }
    }
    public String id() { return id; }
    public synchronized Transcript transcript() { return new Transcript(2,id,startedAt,endedAt,roster,topics,evidence,outcome); }
    public synchronized boolean isFinished() { return finished; }
    public void adjourn() {
        if (manager!=null) manager.requestStop();
        Thread running=worker; if (running!=null) running.interrupt();
    }
    public synchronized void addSpeakerRuling(String ruling) {
        if (finished) throw new IllegalStateException("The House has already adjourned");
        if (ruling==null || ruling.isBlank() || ruling.trim().length()>500) throw new IllegalArgumentException("Rulings need 1–500 characters");
        manager.addSpeakerRuling(ruling.trim());
    }
    public synchronized boolean hasMoreEvents(int index) { return !finished || index<view.size(); }
    public synchronized List<String> awaitEvents(int index,long timeout) throws InterruptedException {
        if (index<0) throw new IllegalArgumentException("Invalid event index");
        if (index>=view.size() && !finished) wait(timeout);
        return index>=view.size() ? List.of() : List.copyOf(view.subList(index,view.size()));
    }
    @Override public synchronized void publicEvent(PublicEvent event) {
        evidence.add(event);
        try { store.saveTranscript(transcript()); }
        catch (RuntimeException e) { evidence.remove(evidence.size()-1); throw e; }
        record(eventView(event,topics));
    }
    @Override public synchronized void speakerCalled(Participant member,boolean interjection) {
        Map<String,Object> event=memberFields(member);
        event.put("type","calling"); event.put("interjection",interjection); record(event);
    }
    private synchronized void record(Object event) {
        view.add(event instanceof String text ? text : Json.write(event));
        store.saveView(id,view);
        notifyAll();
    }
    private synchronized void finish(Transcript.Outcome result) {
        outcome=result; endedAt=System.currentTimeMillis();
        store.saveTranscript(transcript());
        record(Map.of("type","adjourned","outcome",wireOutcome(result),"at",endedAt));
        finished=true; notifyAll();
    }
    private Map<String,Object> sittingEvent() { return sittingEvent(id,startedAt,roster,topics,setup); }
    private static Map<String,Object> sittingEvent(String id,long startedAt,List<Participant> roster,List<Topic> topics,PrivateSetup setup) {
        List<Map<String,Object>> members=new ArrayList<>();
        for (var member:roster) {
            Map<String,Object> fields=memberFields(member);
            String strategy=setup.settings().members().stream().filter(value -> value.party()==member.party())
                    .map(value -> value.strategy().name()).findFirst().orElse("NONE");
            fields.put("strategy",strategy); fields.put("assignmentKnown",!setup.resolvedPrompts().isEmpty()); members.add(fields);
        }
        return Map.of("type","sitting","id",id,"startedAt",startedAt,"rounds",setup.settings().rounds(),
                      "topics",topics.stream().map(Topic::title).toList(),"members",members,
                      "demonstration",!setup.resolvedPrompts().isEmpty() && setup.settings().members().stream().allMatch(member -> setup.configuration().models().get(member.modelPreset()).provider().equals("demo")));
    }
    private static Map<String,Object> memberFields(Participant member) {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("memberId",member.id()); fields.put("name",member.name()); fields.put("party",member.party().name()); fields.put("partyName",member.partyName());
        return fields;
    }
    private static Map<String,Object> eventView(PublicEvent evidence,List<Topic> topics) {
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("turnId",evidence.id()); event.put("topicId",evidence.topicId());
        switch (evidence.type()) {
            case TOPIC -> { event.put("type","topic"); event.put("topic",evidence.text());
                event.put("number",topics.stream().map(Topic::id).toList().indexOf(evidence.topicId())+1); event.put("total",topics.size()); }
            case CHAIR, CHAIR_RULING -> { event.put("type","chair"); event.put("text",evidence.text()); event.put("ruling",evidence.type()==PublicEvent.Type.CHAIR_RULING); }
            case SPEECH, INTERJECTION -> { event.putAll(memberFields(evidence.speaker())); event.put("type","speech");
                event.put("text",evidence.text()); event.put("interjection",evidence.type()==PublicEvent.Type.INTERJECTION); }
        }
        return event;
    }
    private static List<String> rebuildView(Transcript transcript,PrivateSetup setup) {
        List<String> result=new ArrayList<>();
        result.add(Json.write(sittingEvent(transcript.runId(),transcript.startedAt(),transcript.roster(),transcript.topics(),setup)));
        for (var evidence:transcript.events()) result.add(Json.write(eventView(evidence,transcript.topics())));
        if (transcript.outcome()!=Transcript.Outcome.RUNNING)
            result.add(Json.write(Map.of("type","adjourned","outcome",wireOutcome(transcript.outcome()),"at",transcript.endedAt())));
        return result;
    }
    private static String wireOutcome(Transcript.Outcome outcome) {
        return switch (outcome) { case COMPLETE -> "complete"; case ADJOURNED -> "adjourned"; case INTERRUPTED -> "interrupted"; default -> "error"; };
    }
}
