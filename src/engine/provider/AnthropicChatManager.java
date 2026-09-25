package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;
import engine.utils.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AnthropicChatManager implements ChatManager {
    private final String key;
    private final HttpClient client;
    private final URI endpoint;
    public AnthropicChatManager(String key) {
        this(key, ProviderHttp.client(), URI.create("https://api.anthropic.com/v1/messages"));
    }
    AnthropicChatManager(String key, HttpClient client, URI endpoint) {
        this.key = ProviderHttp.credential(key); this.client = client; this.endpoint = endpoint;
    }
    @Override public ChatResponse complete(ChatRequest request) {
        ProviderCapabilities.check(request, "anthropic");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().model());
        body.put("system", request.systemInstructions());
        body.put("messages", ChatCompletionsProvider.messages(request, false));
        body.put("max_tokens", request.model().maxCompletionTokens());
        if (request.model().temperature() != null) body.put("temperature", request.model().temperature());
        if (request.outputSchema() != null) body.put("output_config", Map.of("format",
                Map.of("type", "json_schema", "schema", request.outputSchema().value())));
        long start = System.nanoTime();
        String response = ProviderHttp.post(client, endpoint, Map.of("x-api-key", key, "anthropic-version", "2023-06-01"),
                body, request.model().timeoutSeconds(), "Anthropic");
        try {
            JsonNode root = Json.read(response, JsonNode.class);
            if (!root.path("content").isArray()) throw new IllegalArgumentException();
            CompletionStatus status = switch (root.path("stop_reason").asText()) {
                case "end_turn", "stop_sequence" -> CompletionStatus.COMPLETED;
                case "max_tokens", "model_context_window_exceeded" -> CompletionStatus.TRUNCATED;
                case "refusal" -> CompletionStatus.REFUSED;
                default -> CompletionStatus.UNSUPPORTED;
            };
            if (root.path("stop_details").path("type").asText().equals("refusal")) status = CompletionStatus.REFUSED;
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if (block.path("type").asText().equals("tool_use")) status = CompletionStatus.UNSUPPORTED;
                if (block.path("type").asText().equals("text") && block.path("text").isTextual()) text.append(block.path("text").textValue());
            }
            return Responses.create(root, text.toString(), "anthropic", request.model().model(), status,
                    root.path("usage").path("input_tokens"), root.path("usage").path("output_tokens"), start);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Anthropic returned an invalid chat response");
        }
    }
    @Override public String toString() { return "AnthropicChatManager[credentials redacted]"; }
}
