package engine.agent;

import engine.ChatManager;

public class Agent {

    private final String name;
    private final Party party;
    private final AdversarialStrategy strategy;
    private final ChatManager chatManager;

    public Agent(String name, Party party, AdversarialStrategy strategy, ChatManager chatManager, String systemPrompt) {
        this.name = name;
        this.party = party;
        this.strategy = strategy;
        this.chatManager = chatManager;
        chatManager.addMessage(systemPrompt);
    }

    public String getName() {
        return name;
    }

    public Party getParty() {
        return party;
    }

    public AdversarialStrategy getStrategy() {
        return strategy;
    }

    public boolean isAdversarial() {
        return strategy != AdversarialStrategy.NONE;
    }

    public void hear(String speakerName, String statement) {
        chatManager.addMessage(speakerName + ": " + statement);
    }

    public String speak(String cue) {
        chatManager.addMessage(cue);
        chatManager.sendChat();
        return chatManager.getMessageContent();
    }
}
