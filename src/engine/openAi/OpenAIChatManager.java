package engine.openAi;

import engine.ChatManager;
import engine.utils.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAIChatManager implements ChatManager {

    private static final String CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

    // TODO consider refactoring these parameters into an enum or similar
    private static final String MODEL = "gpt-4o-mini";
    private static final double TEMPERATURE = 1;
    private static final String MODEL2 = "gpt-5-nano";
    private static final String REASONING = "medium"; // minimal, low, medium, high

    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<ChatMessage> messages = new ArrayList<>();
    private String lastResponse;

    public OpenAIChatManager(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void addMessage(String message) {
        // ChatManager has no role parameter, so by convention the first message on a
        // fresh manager seeds the system prompt and every later call is a user turn.
        String role = messages.isEmpty() ? "system" : "user";
        messages.add(new ChatMessage(role, message));
    }

    @Override
    public void sendChat() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_COMPLETIONS_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBodyLegacy()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "OpenAI API request failed (status " + response.statusCode() + "): " + response.body());
            }
            lastResponse = extractContent(response.body());
            messages.add(new ChatMessage("assistant", lastResponse));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to contact OpenAI API", e);
        }
    }

    @Override
    public String getMessageContent() {
        return lastResponse;
    }

    /**
     * This request body builder is used for old models such as the GPT-4 series and older.
     * These use a temperature parameter and message input
     * @return String object of the request body
     */
    private String buildRequestBodyLegacy() {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(Json.escape(MODEL)).append("\",");
        body.append("\"temperature\":").append(TEMPERATURE).append(",");
        body.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                body.append(",");
            }
            ChatMessage message = messages.get(i);
            body.append("{\"role\":\"").append(message.role).append("\",\"content\":\"")
                    .append(Json.escape(message.content)).append("\"}");
        }
        body.append("]}");
        return body.toString();
    }

    /**
     * TODO get this working
     * This request body builder is used for newer models such as the GPT-5 series.
     * These use the
     * @return String object of the request body
     */
    private String buildRequestBodyNew() {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(Json.escape(MODEL2)).append("\",");
        body.append("\"reasoning\":{\"effort\":\"").append(Json.escape(REASONING)).append("\"},");
        body.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                body.append(",");
            }
            ChatMessage message = messages.get(i);
            body.append("{\"role\":\"").append(message.role).append("\",\"content\":\"")
                    .append(Json.escape(message.content)).append("\"}");
        }
        body.append("]}");
                                    System.out.println(body);
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(responseBody);
        List<Object> choices = (List<Object>) root.get("choices");
        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> messageObject = (Map<String, Object>) firstChoice.get("message");
        return (String) messageObject.get("content");
    }

    private static final class ChatMessage {
        private final String role;
        private final String content;

        private ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}