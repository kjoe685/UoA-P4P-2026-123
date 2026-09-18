package engine.debate;

import engine.agent.Agent;
import engine.config.InterruptionConfig;
import engine.io.EngineOutput;
import engine.prompt.PromptManager;
import engine.prompt.TemplateName;
import engine.transcript.DebateEvent;
import engine.transcript.EventType;
import engine.transcript.Participant;
import engine.transcript.Transcript;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Owns turn order and the canonical public transcript, never broadcasts Agent objects. */
public final class DebateManager {
    private final List<Agent> agents;
    private final List<String> topics;
    private final int roundsPerTopic;
    private final EngineOutput output;
    private final PromptManager prompts;
    private final InterruptionConfig interruptions;
    private final Random random;
    private final List<DebateEvent> events = new ArrayList<>();
    private boolean started;

    public DebateManager(List<Agent> agents, List<String> topics, int roundsPerTopic,
                         EngineOutput output, PromptManager prompts, InterruptionConfig interruptions) {
        this.agents = List.copyOf(agents);
        this.topics = List.copyOf(topics);
        if (agents.isEmpty() || topics.isEmpty() || topics.stream().anyMatch(String::isBlank) || roundsPerTopic < 1) {
            throw new IllegalArgumentException("A debate requires agents, topics, and positive rounds");
        }
        var ids = new HashSet<String>();
        for (Agent agent : agents) {
            if (!ids.add(agent.identity().id())) throw new IllegalArgumentException("Duplicate participant ID");
        }
        this.roundsPerTopic = roundsPerTopic;
        this.output = Objects.requireNonNull(output);
        this.prompts = Objects.requireNonNull(prompts);
        this.interruptions = Objects.requireNonNull(interruptions);
        this.random = new Random(interruptions.seed());
    }

    public Transcript transcript() { return new Transcript(events); }

    public Transcript run() {
        if (started) throw new IllegalStateException("Create a new debate for each run");
        started = true;
        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            String topic = topics.get(topicIndex);
            publish(topicIndex, topic, 0, EventType.TOPIC_ANNOUNCEMENT, null,
                    prompts.cue(TemplateName.TOPIC_ANNOUNCEMENT, topic));
            boolean firstSpeech = true;
            for (int round = 1; round <= roundsPerTopic; round++) {
                for (Agent speaker : agents) {
                    TemplateName cue = firstSpeech
                            ? (topicIndex == 0 ? TemplateName.OPENING : TemplateName.NEW_TOPIC)
                            : TemplateName.FOLLOW_UP;
                    String speech = speaker.speak(transcript(), prompts.cue(cue, topic));
                    publish(topicIndex, topic, round, EventType.SPEECH, speaker.identity(), speech);
                    firstSpeech = false;
                    maybeInterject(speaker, topicIndex, topic, round);
                }
            }
        }
        return transcript();
    }

    private void maybeInterject(Agent speaker, int topicIndex, String topic, int round) {
        List<Agent> others = new ArrayList<>(agents);
        others.remove(speaker);
        Collections.shuffle(others, random);
        for (Agent candidate : others) {
            if (candidate.shouldInterject(random, interruptions)) {
                String speech = candidate.speak(transcript(), prompts.cue(TemplateName.INTERJECTION, topic));
                publish(topicIndex, topic, round, EventType.INTERJECTION, candidate.identity(), speech);
                return;
            }
        }
    }

    private void publish(int topicIndex, String topic, int round, EventType type, Participant speaker, String text) {
        DebateEvent event = new DebateEvent(events.size() + 1L, topicIndex, topic, round, type, speaker, text);
        events.add(event);
        output.displayEvent(event);
    }
}
