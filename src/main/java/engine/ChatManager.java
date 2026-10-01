package engine;

@FunctionalInterface
public interface ChatManager {
    engine.chat.ChatResponse complete(engine.chat.ChatRequest request);
}
