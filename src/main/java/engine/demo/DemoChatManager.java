package engine.demo;

import engine.ChatManager;

/** Deterministic UI demonstration. Never contacts a model or reads credentials. */
public final class DemoChatManager implements ChatManager {
    private String response;
    private int turn;
    private String lastPublicStatement = "the question before the House";

    @Override public void addMessage(String message) {
        if (message.contains(": ")) lastPublicStatement = message.substring(message.indexOf(": ") + 2);
    }

    @Override public void sendChat() {
        try { Thread.sleep(250); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generation cancelled");
        }
        response = ++turn == 1
                ? "Mr Speaker, this demonstration opens the question before the House. We should consider the public benefit and the practical costs. I ask members to explain the evidence behind their proposals."
                : "Mr Speaker, I have heard the preceding contribution. The House should test that claim against its likely costs and benefits. I would support a reasoned amendment that addresses those concerns.";
    }

    @Override public String getMessageContent() { return response; }
}
