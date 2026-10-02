package engine.debate;

import engine.agent.Agent;
import engine.config.InterruptionConfig;
import engine.io.EngineOutput;
import engine.prompt.*;
import engine.transcript.*;
import java.util.*;

/** Main's single scheduler, preserving chair rulings, progress and cancellation. */
public final class DebateManager {
    private final List<Agent> agents;
    private final List<Topic> topics;
    private final int rounds;
    private final EngineOutput output;
    private final PromptManager prompts;
    private final InterruptionConfig interruptions;
    private final Random random;
    private final Queue<String> rulings=new ArrayDeque<>();
    private final List<PublicEvent> evidence=new ArrayList<>();
    private final Object publicationLock=new Object();
    private volatile boolean stopped;
    private boolean acceptingRulings=true;
    private Thread generationThread;
    private String currentTopic;
    public DebateManager(List<Agent> agents,List<Topic> topics,int rounds,EngineOutput output,
                         PromptManager prompts,InterruptionConfig interruptions) {
        this.agents=List.copyOf(agents); this.topics=List.copyOf(topics); this.rounds=rounds;
        this.output=output; this.prompts=prompts; this.interruptions=interruptions;
        this.random=new Random(interruptions.seed());
    }
    public void run() {
        synchronized (publicationLock) { generationThread=Thread.currentThread(); }
        try { runDebate(); }
        finally {
            boolean interrupted;
            synchronized (publicationLock) {
                // Close submission before flushing so a final-turn or cancelled ruling cannot be lost.
                acceptingRulings=false; generationThread=null;
                interrupted=Thread.interrupted();
                try {
                    if (currentTopic==null) currentTopic=topics.get(0).id();
                    drainRulings(true);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }
    private void runDebate() {
        boolean firstOverall=true;
        for (Topic topic:topics) {
            if (!beginTopic(topic)) return;
            if (!firstOverall && !emit(PublicEvent.Type.CHAIR,null,prompts.cue(TemplateName.TOPIC_ANNOUNCEMENT,topic.title()))) return;
            boolean firstOnTopic=true;
            for (int round=0;round<rounds;round++) {
                for (Agent speaker:agents) {
                    if (!drainRulings(false)) return;
                    if (stopped) return;
                    TemplateName cue=firstOverall ? TemplateName.OPENING : firstOnTopic ? TemplateName.NEW_TOPIC : TemplateName.FOLLOW_UP;
                    firstOverall=false; firstOnTopic=false;
                    output.speakerCalled(speaker.identity(),false);
                    String speech=speaker.speak(List.copyOf(evidence),prompts.turnCue(cue,topic,speaker.identity(),evidence));
                    if (!emit(PublicEvent.Type.SPEECH,speaker.identity(),speech)) return;
                    List<Agent> others=new ArrayList<>(agents); others.remove(speaker); Collections.shuffle(others,random);
                    for (Agent candidate:others) {
                        if (stopped) return;
                        if (candidate.shouldInterject(random,interruptions)) {
                            if (!drainRulings(false)) return;
                            output.speakerCalled(candidate.identity(),true);
                            String text=candidate.speak(List.copyOf(evidence),prompts.turnCue(TemplateName.INTERJECTION,topic,candidate.identity(),evidence));
                            if (!emit(PublicEvent.Type.INTERJECTION,candidate.identity(),text)) return;
                            break;
                        }
                    }
                }
            }
        }
    }
    private boolean beginTopic(Topic topic) {
        synchronized (publicationLock) {
            if (stopped || Thread.currentThread().isInterrupted()) return false;
            // A ruling queued on the preceding topic must retain that topic's evidence context.
            if (currentTopic!=null) drainRulings(false);
            currentTopic=topic.id();
            publish(PublicEvent.Type.TOPIC,null,topic.title());
            return true;
        }
    }
    private boolean emit(PublicEvent.Type type,Participant speaker,String text) {
        synchronized (publicationLock) {
            if (stopped || Thread.currentThread().isInterrupted()) return false;
            publish(type,speaker,text);
            return true;
        }
    }
    private boolean drainRulings(boolean closing) {
        synchronized (publicationLock) {
            if (!closing && (stopped || Thread.currentThread().isInterrupted())) return false;
            String ruling;
            while ((ruling=rulings.poll())!=null) publish(PublicEvent.Type.CHAIR_RULING,null,ruling);
            return true;
        }
    }
    private void publish(PublicEvent.Type type,Participant speaker,String text) {
        var event=new PublicEvent("turn-"+(evidence.size()+1),currentTopic,type,speaker,text);
        output.publicEvent(event); // Persistence adapter must succeed before publication.
        evidence.add(event);
    }
    public void addSpeakerRuling(String ruling) {
        synchronized (publicationLock) {
            if (!acceptingRulings) throw new IllegalStateException("The House has already adjourned");
            rulings.add(ruling);
        }
    }
    public void requestStop() {
        synchronized (publicationLock) {
            if (!acceptingRulings) return;
            stopped=true; acceptingRulings=false;
            // Interrupt generation under the same lock as final persistence, never during the flush.
            if (generationThread!=null && generationThread!=Thread.currentThread()) generationThread.interrupt();
        }
    }
    public boolean isStopRequested() { return stopped; }
}
