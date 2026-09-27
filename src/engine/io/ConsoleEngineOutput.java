package engine.io;

import engine.agent.Agent;

public class ConsoleEngineOutput implements EngineOutput {

    @Override
    public void topicStarted(int topicNumber, int totalTopics, String topic) {
        System.out.println("Starting debate on: \"" + topic + "\"");
        System.out.println();
    }

    @Override
    public void displaySpeakerMessage(String message) {
        print("The Speaker", message);
    }

    @Override
    public void displayMessage(Agent speaker, String message, boolean interjection) {
        print(interjection ? speaker.getName() + " (interjecting)" : speaker.getName(), message);
    }

    private void print(String persona, String message) {
        System.out.println("[" + persona + "]");
        System.out.println(message);
        System.out.println();
    }
}
