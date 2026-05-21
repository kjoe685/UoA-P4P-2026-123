package engine;

public interface ChatManager {
    void addMessage(String message);
    void sendChat();
    String getMessageContent();
}
