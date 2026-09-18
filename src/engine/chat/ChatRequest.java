package engine.chat;

import engine.config.ModelConfig;
import java.util.List;
import java.util.Objects;

/** One recipient's instructions and messages. No run configuration, agent list, or credentials. */
public record ChatRequest(String systemInstructions, List<ChatMessage> messages, ModelConfig model) {
    public ChatRequest {
        Objects.requireNonNull(systemInstructions, "systemInstructions");
        if (systemInstructions.isBlank()) throw new IllegalArgumentException("System instructions are required");
        messages = List.copyOf(messages);
        Objects.requireNonNull(model, "model");
    }

    @Override public String toString() { return "ChatRequest[private content redacted]"; }
}
