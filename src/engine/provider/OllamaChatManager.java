package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;
import engine.utils.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Serial local inference with a fixed context budget; no downloads or cloud fallback. */
public final class OllamaChatManager implements ChatManager {
    private static final Semaphore GENERATION = new Semaphore(1, true);
    private final HttpClient client;
    private final URI endpoint;
    private final int contextTokens;
    public OllamaChatManager(URI base, int contextTokens) {
        this(ProviderHttp.client(), validateBase(base).resolve("/api/chat"), contextTokens);
    }
    OllamaChatManager(HttpClient client, URI endpoint, int contextTokens) {
        if (contextTokens < 1024 || contextTokens > 131072) throw new IllegalArgumentException("Invalid Ollama context limit");
        this.client = client; this.endpoint = endpoint; this.contextTokens = contextTokens;
    }
    private static URI validateBase(URI base) {
        if (base == null || !"http".equals(base.getScheme()) || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || base.getHost() == null
                || !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost())
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Ollama must use a loopback HTTP base URL");
        return base;
    }
    @Override public ChatResponse complete(ChatRequest request) {
        ProviderCapabilities.check(request, "ollama");
        if (request.model().model().endsWith(":cloud") || request.model().model().endsWith("-cloud"))
            throw new IllegalArgumentException("Select a local Ollama model");
        var messages = ChatCompletionsProvider.messages(request, true);
        // UTF-8 bytes plus per-message overhead are a deliberately conservative token upper estimate.
        long estimated = Json.write(messages).getBytes(StandardCharsets.UTF_8).length + 256L + messages.size() * 32L;
        if (estimated + request.model().maxCompletionTokens() > contextTokens)
            throw new IllegalArgumentException("Request exceeds the conservative Ollama context budget");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_ctx", contextTokens);
        options.put("num_predict", request.model().maxCompletionTokens());
        if (request.model().temperature() != null) options.put("temperature", request.model().temperature());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model().model()); body.put("messages", messages);
        body.put("stream", false); body.put("options", options);
        if (request.outputSchema() != null) body.put("format", request.outputSchema().value());
        boolean acquired = false;
        long start = System.nanoTime();
        try {
            acquired = GENERATION.tryAcquire(request.model().timeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("Ollama generation queue timed out");
            long remaining = TimeUnit.SECONDS.toNanos(request.model().timeoutSeconds()) - (System.nanoTime() - start);
            if (remaining <= 0) throw new IllegalStateException("Ollama generation queue timed out");
            String response = ProviderHttp.post(client, endpoint, Map.of(), body,
                    (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining)), "Ollama");
            try {
                JsonNode root = Json.read(response, JsonNode.class);
                if (!root.path("done").isBoolean() || !root.path("done").booleanValue() || root.hasNonNull("error"))
                    throw new IllegalArgumentException();
                CompletionStatus status = switch (root.path("done_reason").asText()) {
                    case "stop" -> CompletionStatus.COMPLETED;
                    case "length" -> CompletionStatus.TRUNCATED;
                    default -> CompletionStatus.UNSUPPORTED;
                };
                JsonNode message = root.path("message");
                if (message.path("tool_calls").isArray() && !message.path("tool_calls").isEmpty()) status = CompletionStatus.UNSUPPORTED;
                return Responses.create(root, message.path("content").isTextual() ? message.path("content").textValue() : null,
                        "ollama", request.model().model(), status, root.path("prompt_eval_count"), root.path("eval_count"), start);
            } catch (IllegalArgumentException e) { throw new IllegalStateException("Ollama returned an invalid chat response"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Ollama request interrupted");
        } finally { if (acquired) GENERATION.release(); }
    }
}
