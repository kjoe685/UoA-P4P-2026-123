package engine.debate;

import engine.agent.Agent;
import engine.io.EngineOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DebateManager {

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

    public DebateManager(List<Agent> agents, List<String> topics, int roundsPerTopic, EngineOutput output) {
        this.agents = agents;
        this.topics = topics;
        this.roundsPerTopic = roundsPerTopic;
        this.output = output;
    }

    public void run() {
        boolean firstSpeechOverall = true;

        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            String topic = topics.get(topicIndex);
            System.out.println("Starting debate on: \"" + topic + "\"");
            System.out.println();

            if (topicIndex > 0) {
                announceNewTopic(topic);
            }

            boolean firstSpeechThisTopic = true;
            for (int round = 1; round <= roundsPerTopic; round++) {
                for (Agent speaker : agents) {
                    String cue = firstSpeechOverall
                            ? OPENING_CUE
                            : firstSpeechThisTopic ? NEW_TOPIC_CUE : FOLLOW_UP_CUE;
                    firstSpeechOverall = false;
                    firstSpeechThisTopic = false;

                    String statement = speaker.speak(cue);
                    output.displayMessage(speaker.getName(), statement);
                    broadcast(speaker, statement);

                    maybeInterject(speaker);
                }
            }
        }
    }

    private void announceNewTopic(String topic) {
        String announcement = "We now move to a new topic: \"" + topic + "\".";
        for (Agent agent : agents) {
            agent.hear("The Speaker", announcement);
        }
    }

    private void maybeInterject(Agent speaker) {
        List<Agent> others = new ArrayList<>(agents);
        others.remove(speaker);
        Collections.shuffle(others);

        for (Agent candidate : others) {
            double chance = candidate.isAdversarial()
                    ? ADVERSARIAL_INTERJECTION_CHANCE
                    : COOPERATIVE_INTERJECTION_CHANCE;
            if (Math.random() < chance) {
                String interjection = candidate.speak(INTERJECTION_CUE);
                output.displayMessage(candidate.getName() + " (interjecting)", interjection);
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
