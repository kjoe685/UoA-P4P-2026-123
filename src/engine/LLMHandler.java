package engine;

public interface LLMHandler {
    void addMessage(String message);
    void sendChat();
    String getMessageContent();
}
