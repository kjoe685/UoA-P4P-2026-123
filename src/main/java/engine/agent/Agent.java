package engine.agent;

import engine.ChatManager;
import engine.chat.*;
import engine.config.*;
import engine.transcript.*;
import engine.utils.Json;
import java.util.*;

/** Recipient-specific private context. The provider holds no conversation state. */
public final class Agent {
    private final Participant identity;
    private final PrivateAgentContext context;
    private final ChatManager provider;
    public Agent(Participant identity, AdversarialStrategy strategy, ChatManager provider,
                 String instructions, List<String> grounding, ModelConfig model) {
        this.identity=Objects.requireNonNull(identity); this.provider=Objects.requireNonNull(provider);
        this.context=new PrivateAgentContext(instructions,strategy,grounding,model);
    }
    public Participant identity() { return identity; }
    public boolean shouldInterject(Random random,InterruptionConfig settings) {
        return random.nextDouble() < (context.strategy==AdversarialStrategy.NONE
                ? settings.cooperativeChance() : settings.adversarialChance());
    }
    public String speak(List<PublicEvent> publicEvidence,String cue) {
        List<ChatMessage> messages=new ArrayList<>();
        for (var event:List.copyOf(publicEvidence)) {
            boolean own=event.speaker()!=null && identity.id().equals(event.speaker().id());
            messages.add(new ChatMessage(own ? ChatMessage.Role.ASSISTANT : ChatMessage.Role.USER,Json.write(event)));
        }
        messages.add(new ChatMessage(ChatMessage.Role.USER,cue));
        return provider.complete(new ChatRequest(context.systemPrompt,messages,context.model)).requireCompletedText();
    }
    @Override public String toString() { return "Agent["+identity.id()+", private context redacted]"; }
}
