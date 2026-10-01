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

    private enum OpenAIPreset {
        // Name, Temperature, Reasoning Effort
        // null for non-applicable parameters
        GPT_4o_MINI("gpt-4o-mini", 1d, null),
        GPT_5_NANO("gpt-5-nano", null, "medium");

        private final String name;
        private final Double temperature;
        private final String reasoningEffort;

        OpenAIPreset(String name, Double temperature, String reasoningEffort) {
            this.name = name;
            this.temperature = temperature;
            this.reasoningEffort = reasoningEffort;
        }

        private String getName() {
            return name;
        }

        private Double getTemperature() {
            return temperature;
        }

        private String getReasoningEffort() {
            return reasoningEffort;
        }
    }

    private static final String CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";
    private static final OpenAIPreset PRESET = OpenAIPreset.GPT_5_NANO;

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
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(PRESET)))
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

    private String buildRequestBody(OpenAIPreset preset) {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(Json.escape(preset.getName())).append("\",");
        if (preset.getTemperature() != null) {
            body.append("\"temperature\":").append(preset.getTemperature()).append(",");
        }
        if (preset.getReasoningEffort() != null) {
            body.append("\"reasoning_effort\":\"")
                    .append(Json.escape(preset.getReasoningEffort())).append("\",");
        }
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
