package engine;

import engine.chat.ChatRequest;
import engine.chat.ChatResponse;

/** Stateless provider contract. Implementations must never retain or reuse conversation history. */
@FunctionalInterface
public interface ChatManager {
    ChatResponse complete(ChatRequest request);
}
