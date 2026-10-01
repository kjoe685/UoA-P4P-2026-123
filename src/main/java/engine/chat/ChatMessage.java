package engine.chat;

import java.util.Objects;

public record ChatMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) throw new IllegalArgumentException("Message content must not be blank");
    }

    @Override public String toString() { return "ChatMessage[" + role + ", content redacted]"; }
}
