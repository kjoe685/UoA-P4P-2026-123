package engine.openAi;

import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;
import engine.chat.ChatResponse.TokenUsage;
import engine.utils.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stateless transport; only credentials and the reusable HTTP client live here. */
public final class OpenAIChatManager implements ChatManager {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private final String apiKey;
    private final HttpClient client;
    private final URI endpoint;

    public OpenAIChatManager(String apiKey) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), ENDPOINT);
    }

    // Package-private transport seam for local mock-server tests, not a user-configurable credential destination.
    OpenAIChatManager(String apiKey, HttpClient client, URI endpoint) {
        // Reject invalid header characters ourselves so HttpRequest never echoes the key in an error.
        if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("A valid API key is required");
        }
        this.apiKey = apiKey;
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (!"openai".equals(request.model().provider())) throw new IllegalArgumentException("Provider mismatch");
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.systemInstructions()));
        request.messages().forEach(message -> messages.add(Map.of(
                "role", message.role().name().toLowerCase(Locale.ROOT), "content", message.content())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().model());
        body.put("messages", messages);
        body.put("max_completion_tokens", request.model().maxCompletionTokens());
        body.put("store", false);
        if (request.model().temperature() != null) body.put("temperature", request.model().temperature());
        if (request.model().reasoningEffort() != null) body.put("reasoning_effort", request.model().reasoningEffort());

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.model().timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                // Never surface an untrusted response body (it may echo credentials or private prompts).
                throw new IllegalStateException("OpenAI request failed (HTTP " + response.statusCode() + ")");
            }
            return parseResponse(response.body(), request.model().model(), (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI request interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Could not contact OpenAI (connection failed or timed out)");
        }
    }

    private ChatResponse parseResponse(String body, String requestedModel, long latency) {
        try {
            JsonNode root = Json.read(body, JsonNode.class);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            if (!choice.isObject() || !message.isObject()) throw new IllegalArgumentException();
            CompletionStatus status;
            if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) {
                status = CompletionStatus.REFUSED;
            } else {
                status = switch (choice.path("finish_reason").asText()) {
                    case "stop" -> CompletionStatus.COMPLETED;
                    case "length" -> CompletionStatus.TRUNCATED;
                    case "content_filter" -> CompletionStatus.REFUSED;
                    default -> CompletionStatus.UNSUPPORTED;
                };
            }
            String text = message.path("content").isTextual() ? message.path("content").textValue() : null;
            if (status == CompletionStatus.COMPLETED && (text == null || text.isBlank())) {
                throw new IllegalArgumentException();
            }
            TokenUsage usage = null;
            JsonNode input = root.path("usage").path("prompt_tokens");
            JsonNode output = root.path("usage").path("completion_tokens");
            if (input.isIntegralNumber() && output.isIntegralNumber() && input.canConvertToLong() && output.canConvertToLong()) {
                usage = new TokenUsage(input.longValue(), output.longValue());
            }
            String model = root.path("model").isTextual() ? root.path("model").textValue() : requestedModel;
            return new ChatResponse(text, "openai", model, status, usage, latency);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("OpenAI returned an invalid chat response");
        }
    }

    @Override public String toString() { return "OpenAIChatManager[credentials redacted]"; }
}
