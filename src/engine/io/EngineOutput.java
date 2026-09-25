package engine.io;

import engine.agent.Agent;

public interface EngineOutput {

    /** A new item on the agenda has started. {@code topicNumber} is 1-based. */
    void topicStarted(int topicNumber, int totalTopics, String topic);

    /** Something said from the chair by the Speaker of the House (topic announcements, rulings). */
    void displaySpeakerMessage(String message);

    /** A member's speech or interjection. */
    void displayMessage(Agent speaker, String message, boolean interjection);

    /** Called just before a member is asked to speak, while their response is still being generated. */
    default void speakerCalled(Agent speaker, boolean interjection) {
    }
}
