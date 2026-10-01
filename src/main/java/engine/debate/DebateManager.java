package engine.debate;

import engine.agent.Agent;
import engine.config.InterruptionConfig;
import engine.io.EngineOutput;
import engine.prompt.*;
import engine.transcript.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Main's single scheduler, preserving chair rulings, progress and cancellation. */
public final class DebateManager {
    private final List<Agent> agents;
    private final List<Topic> topics;
    private final int rounds;
    private final EngineOutput output;
    private final PromptManager prompts;
    private final InterruptionConfig interruptions;
    private final Random random;
    private final Queue<String> rulings=new ConcurrentLinkedQueue<>();
    private final List<PublicEvent> evidence=new ArrayList<>();
    private final Object publicationLock=new Object();
    private volatile boolean stopped;
    private String currentTopic;
    public DebateManager(List<Agent> agents,List<Topic> topics,int rounds,EngineOutput output,
                         PromptManager prompts,InterruptionConfig interruptions) {
        this.agents=List.copyOf(agents); this.topics=List.copyOf(topics); this.rounds=rounds;
        this.output=output; this.prompts=prompts; this.interruptions=interruptions;
        this.random=new Random(interruptions.seed());
    }
    public void run() {
        boolean firstOverall=true;
        for (Topic topic:topics) {
            if (stopped) return;
            currentTopic=topic.id();
            if (!emit(PublicEvent.Type.TOPIC,null,topic.title())) return;
            if (!firstOverall && !emit(PublicEvent.Type.CHAIR,null,prompts.cue(TemplateName.TOPIC_ANNOUNCEMENT,topic.title()))) return;
            boolean firstOnTopic=true;
            for (int round=0;round<rounds;round++) {
                for (Agent speaker:agents) {
                    String ruling;
                    while ((ruling=rulings.poll())!=null) if (!emit(PublicEvent.Type.CHAIR_RULING,null,ruling)) return;
                    if (stopped) return;
                    TemplateName cue=firstOverall ? TemplateName.OPENING : firstOnTopic ? TemplateName.NEW_TOPIC : TemplateName.FOLLOW_UP;
                    firstOverall=false; firstOnTopic=false;
                    output.speakerCalled(speaker.identity(),false);
                    String speech=speaker.speak(List.copyOf(evidence),prompts.cue(cue,topic.title()));
                    if (!emit(PublicEvent.Type.SPEECH,speaker.identity(),speech)) return;
                    List<Agent> others=new ArrayList<>(agents); others.remove(speaker); Collections.shuffle(others,random);
                    for (Agent candidate:others) {
                        if (stopped) return;
                        if (candidate.shouldInterject(random,interruptions)) {
                            output.speakerCalled(candidate.identity(),true);
                            String text=candidate.speak(List.copyOf(evidence),prompts.cue(TemplateName.INTERJECTION,topic.title()));
                            if (!emit(PublicEvent.Type.INTERJECTION,candidate.identity(),text)) return;
                            break;
                        }
                    }
                }
            }
        }
    }
    private boolean emit(PublicEvent.Type type,Participant speaker,String text) {
        synchronized (publicationLock) {
            if (stopped || Thread.currentThread().isInterrupted()) return false;
            var event=new PublicEvent("turn-"+(evidence.size()+1),currentTopic,type,speaker,text);
            output.publicEvent(event); // Persistence adapter must succeed before publication.
            evidence.add(event);
            return true;
        }
    }
    public void addSpeakerRuling(String ruling) { rulings.add(ruling); }
    public void requestStop() { synchronized (publicationLock) { stopped=true; } }
    public boolean isStopRequested() { return stopped; }
}
