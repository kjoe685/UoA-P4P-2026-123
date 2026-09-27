package engine.debate;

import engine.agent.Agent;
import engine.io.EngineOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DebateManager {

    private static final String SPEAKER_NAME = "The Speaker";

    private static final String OPENING_CUE =
            "It is now your turn to open the debate. Give one concise speech (3-5 sentences) in character.";
    private static final String NEW_TOPIC_CUE =
            "It is now your turn to open discussion on this new topic. Give one concise speech (3-5 sentences) "
                    + "in character.";
    private static final String FOLLOW_UP_CUE =
            "It is now your turn to speak again. Respond to the debate so far, in character. "
                    + "Give one concise speech (3-5 sentences).";
    private static final String INTERJECTION_CUE =
            "Interject briefly right now on what was just said — a heckle, a point of order, or a sharp "
                    + "one-line rebuttal. Keep it to 1-2 sentences.";

    private static final double COOPERATIVE_INTERJECTION_CHANCE = 0.15;
    private static final double ADVERSARIAL_INTERJECTION_CHANCE = 0.45;

    private final List<Agent> agents;
    private final List<String> topics;
    private final int roundsPerTopic;
    private final EngineOutput output;

    // Written from other threads (e.g. the web frontend) while run() is in progress.
    private final Queue<String> pendingRulings = new ConcurrentLinkedQueue<>();
    private volatile boolean stopRequested = false;

    public DebateManager(List<Agent> agents, List<String> topics, int roundsPerTopic, EngineOutput output) {
        this.agents = agents;
        this.topics = topics;
        this.roundsPerTopic = roundsPerTopic;
        this.output = output;
    }

    public void run() {
        boolean firstSpeechOverall = true;

        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            if (stopRequested) {
                return;
            }
            String topic = topics.get(topicIndex);
            output.topicStarted(topicIndex + 1, topics.size(), topic);

            if (topicIndex > 0) {
                announceNewTopic(topic);
            }

            boolean firstSpeechThisTopic = true;
            for (int round = 1; round <= roundsPerTopic; round++) {
                for (Agent speaker : agents) {
                    deliverSpeakerRulings();
                    if (stopRequested) {
                        return;
                    }

                    String cue = firstSpeechOverall
                            ? OPENING_CUE
                            : firstSpeechThisTopic ? NEW_TOPIC_CUE : FOLLOW_UP_CUE;
                    firstSpeechOverall = false;
                    firstSpeechThisTopic = false;

                    output.speakerCalled(speaker, false);
                    String statement = speaker.speak(cue);
                    output.displayMessage(speaker, statement, false);
                    broadcast(speaker, statement);

                    maybeInterject(speaker);
                }
            }
        }
    }

    /**
     * Queues a ruling from the Speaker of the House. It is read out to every agent before the next scheduled
     * speech, so it can steer the rest of the debate (e.g. calling an adversarial member back to the topic).
     * Safe to call from another thread while {@link #run()} is in progress.
     */
    public void addSpeakerRuling(String ruling) {
        pendingRulings.add(ruling);
    }

    /** Asks the debate to stop before the next speech. Safe to call from another thread. */
    public void requestStop() {
        stopRequested = true;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    private void announceNewTopic(String topic) {
        String announcement = "We now move to a new topic: \"" + topic + "\".";
        output.displaySpeakerMessage(announcement);
        for (Agent agent : agents) {
            agent.hear(SPEAKER_NAME, announcement);
        }
    }

    private void deliverSpeakerRulings() {
        String ruling;
        while ((ruling = pendingRulings.poll()) != null) {
            output.displaySpeakerMessage(ruling);
            for (Agent agent : agents) {
                agent.hear(SPEAKER_NAME, ruling);
            }
        }
    }

    private void maybeInterject(Agent speaker) {
        if (stopRequested) {
            return;
        }
        List<Agent> others = new ArrayList<>(agents);
        others.remove(speaker);
        Collections.shuffle(others);

        for (Agent candidate : others) {
            double chance = candidate.isAdversarial()
                    ? ADVERSARIAL_INTERJECTION_CHANCE
                    : COOPERATIVE_INTERJECTION_CHANCE;
            if (Math.random() < chance) {
                output.speakerCalled(candidate, true);
                String interjection = candidate.speak(INTERJECTION_CUE);
                output.displayMessage(candidate, interjection, true);
                broadcast(candidate, interjection);
                return;
            }
        }
    }

    private void broadcast(Agent speaker, String statement) {
        for (Agent listener : agents) {
            if (listener != speaker) {
                listener.hear(speaker.getName(), statement);
            }
        }
    }
}
