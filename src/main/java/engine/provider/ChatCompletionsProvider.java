package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;
import engine.utils.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared OpenAI/xAI wire protocol; provider-specific options remain explicit. */
public class ChatCompletionsProvider implements ChatManager {
    private final String provider;
    private final String label;
    private final String key;
    private final HttpClient client;
    private final URI endpoint;
    protected ChatCompletionsProvider(String provider, String label, String key, HttpClient client, URI endpoint) {
        this.provider = provider;
        this.label = label;
        this.key = ProviderHttp.credential(key);
        this.client = client;
        this.endpoint = endpoint;
    }
    public static List<Map<String, String>> messages(ChatRequest request, boolean includeSystem) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (includeSystem) messages.add(Map.of("role", "system", "content", request.systemInstructions()));
        request.messages().forEach(message -> messages.add(Map.of("role", message.role().name().toLowerCase(Locale.ROOT),
                "content", message.content())));
        return messages;
    }
    @Override public ChatResponse complete(ChatRequest request) {
        ProviderCapabilities.check(request, provider);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().model());
        body.put("messages", messages(request, true));
        body.put(provider.equals("openai") ? "max_completion_tokens" : "max_tokens", request.model().maxCompletionTokens());
        if (provider.equals("openai")) body.put("store", false);
        if (request.model().temperature() != null) body.put("temperature", request.model().temperature());
        if (request.model().reasoningEffort() != null) body.put("reasoning_effort", request.model().reasoningEffort());
        if (request.outputSchema() != null) body.put("response_format", Map.of("type", "json_schema", "json_schema",
                Map.of("name", request.outputSchema().name(), "strict", true, "schema", request.outputSchema().value())));
        long start = System.nanoTime();
        String response = ProviderHttp.post(client, endpoint, Map.of("Authorization", "Bearer " + key), body,
                request.model().timeoutSeconds(), label);
        try {
            JsonNode root = Json.read(response, JsonNode.class);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1) throw new IllegalArgumentException();
            JsonNode choice = choices.get(0), message = choice.path("message");
            if (!message.isObject()) throw new IllegalArgumentException();
            CompletionStatus status = message.hasNonNull("refusal") ? CompletionStatus.REFUSED : switch (choice.path("finish_reason").asText()) {
                case "stop" -> CompletionStatus.COMPLETED;
                case "length" -> CompletionStatus.TRUNCATED;
                case "content_filter" -> CompletionStatus.REFUSED;
                default -> CompletionStatus.UNSUPPORTED;
            };
            if (message.hasNonNull("tool_calls") && !message.path("tool_calls").isEmpty()
                    || message.hasNonNull("function_call")) status = CompletionStatus.UNSUPPORTED;
            return Responses.create(root, message.path("content").isTextual() ? message.path("content").textValue() : null,
                    provider, request.model().model(), status, root.path("usage").path("prompt_tokens"),
                    root.path("usage").path("completion_tokens"), start);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(label + " returned an invalid chat response");
        }
    }
    @Override public String toString() { return label + "ChatManager[credentials redacted]"; }
}
