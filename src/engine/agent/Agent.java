package engine.agent;

import engine.ChatManager;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.config.InterruptionConfig;
import engine.config.ModelConfig;
import engine.transcript.Participant;
import engine.transcript.Transcript;
import engine.utils.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public final class Agent {
    private final Participant identity;
    private final PrivateAgentContext context;
    private final ChatManager chatManager;

    public Agent(Participant identity, AdversarialStrategy strategy, ChatManager chatManager,
                 String systemPrompt, List<String> groundingExcerpts, ModelConfig model) {
        this.identity = Objects.requireNonNull(identity);
        this.context = new PrivateAgentContext(systemPrompt, strategy, groundingExcerpts, model);
        this.chatManager = Objects.requireNonNull(chatManager);
    }

    public Participant identity() { return identity; }

    public boolean shouldInterject(Random random, InterruptionConfig settings) {
        double chance = context.strategy == AdversarialStrategy.NONE
                ? settings.cooperativeChance() : settings.adversarialChance();
        return random.nextDouble() < chance;
    }

    public String speak(Transcript publicTranscript, String cue) {
        List<ChatMessage> messages = new ArrayList<>();
        for (var event : publicTranscript.events()) {
            boolean ownSpeech = event.speaker() != null && event.speaker().id().equals(identity.id());
            messages.add(new ChatMessage(ownSpeech ? ChatMessage.Role.ASSISTANT : ChatMessage.Role.USER,
                    Json.write(event)));
        }
        messages.add(new ChatMessage(ChatMessage.Role.USER, cue));
        return chatManager.complete(new ChatRequest(context.systemPrompt, messages, context.model)).requireCompletedText();
    }

    @Override public String toString() { return "Agent[" + identity.id() + "]"; }
}
