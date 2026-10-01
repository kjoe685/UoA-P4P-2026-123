package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;
import engine.utils.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native generateContent API. Thought parts never become public speech. */
public final class GeminiChatManager implements ChatManager {
    private final String key;
    private final HttpClient client;
    private final URI base;
    public GeminiChatManager(String key) {
        this(key, ProviderHttp.client(), URI.create("https://generativelanguage.googleapis.com/v1beta/models/"));
    }
    GeminiChatManager(String key, HttpClient client, URI base) {
        this.key = ProviderHttp.credential(key); this.client = client; this.base = base;
    }
    @Override public ChatResponse complete(ChatRequest request) {
        ProviderCapabilities.check(request, "gemini");
        var contents = request.messages().stream().map(m -> Map.of("role",
                m.role() == ChatMessage.Role.ASSISTANT ? "model" : "user", "parts", List.of(Map.of("text", m.content())))).toList();
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("maxOutputTokens", request.model().maxCompletionTokens());
        generation.put("candidateCount", 1);
        if (request.model().temperature() != null) generation.put("temperature", request.model().temperature());
        if (request.outputSchema() != null) {
            generation.put("responseMimeType", "application/json");
            generation.put("responseJsonSchema", request.outputSchema().value());
        }
        var body = Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemInstructions()))),
                "contents", contents, "generationConfig", generation);
        long start = System.nanoTime();
        String response = ProviderHttp.post(client, URI.create(base.toString() + request.model().model() + ":generateContent"),
                Map.of("x-goog-api-key", key), body, request.model().timeoutSeconds(), "Gemini");
        try {
            JsonNode root = Json.read(response, JsonNode.class);
            JsonNode candidates = root.path("candidates");
            CompletionStatus status;
            StringBuilder text = new StringBuilder();
            if (root.path("promptFeedback").hasNonNull("blockReason")) status = CompletionStatus.REFUSED;
            else {
                if (!candidates.isArray() || candidates.size() != 1) throw new IllegalArgumentException();
                JsonNode candidate = candidates.get(0);
                status = switch (candidate.path("finishReason").asText()) {
                    case "STOP" -> CompletionStatus.COMPLETED;
                    case "MAX_TOKENS" -> CompletionStatus.TRUNCATED;
                    case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> CompletionStatus.REFUSED;
                    default -> CompletionStatus.UNSUPPORTED;
                };
                for (JsonNode part : candidate.path("content").path("parts")) {
                    if (part.hasNonNull("functionCall")) status = CompletionStatus.UNSUPPORTED;
                    if (!part.path("thought").asBoolean(false) && part.path("text").isTextual()) text.append(part.path("text").textValue());
                }
            }
            // Completion usage includes reasoning tokens, just as the OpenAI adapter does.
            JsonNode usage = root.path("usageMetadata");
            JsonNode output = usage.path("candidatesTokenCount");
            JsonNode thoughts = usage.path("thoughtsTokenCount");
            if (output.isIntegralNumber() && thoughts.isIntegralNumber()) {
                if (output.longValue() < 0 || thoughts.longValue() < 0) throw new IllegalArgumentException();
                output = com.fasterxml.jackson.databind.node.LongNode.valueOf(Math.addExact(output.longValue(), thoughts.longValue()));
            }
            var result = Responses.create(root, text.toString(), "gemini", request.model().model(), status,
                    usage.path("promptTokenCount"), output, start);
            return new ChatResponse(result.text(), result.provider(), root.path("modelVersion").isTextual()
                    ? root.path("modelVersion").textValue() : result.model(), result.status(), result.usage(), result.latencyMillis());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalStateException("Gemini returned an invalid chat response");
        }
    }
    @Override public String toString() { return "GeminiChatManager[credentials redacted]"; }
}
