package engine.provider;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import engine.ChatManager;
import engine.chat.*;
import engine.config.ModelConfig;
import engine.utils.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProviderTest {
    private HttpServer server;
    private URI base;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
    private volatile String response;
    private volatile int status = 200;
    private volatile boolean rateLimitFirst;
    private volatile long delayMillis;
    private final AtomicInteger calls = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                int count = calls.incrementAndGet();
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                headers.add(Map.copyOf(exchange.getRequestHeaders()));
                if (delayMillis > 0) try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(rateLimitFirst && count == 1 ? 429 : status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
    @AfterEach void stop() { server.stop(0); }
    private ChatManager provider(String name) {
        var client = ProviderHttp.client();
        return switch (name) {
            case "gemini" -> new GeminiChatManager("KEY_SENTINEL", client, base);
            case "grok" -> new GrokChatManager("KEY_SENTINEL", client, base);
            case "anthropic" -> new AnthropicChatManager("KEY_SENTINEL", client, base);
            case "ollama" -> new OllamaChatManager(client, base, 16384);
            default -> throw new IllegalArgumentException();
        };
    }
    private String model(String name) {
        return switch (name) { case "anthropic" -> "claude-sonnet-4-6"; case "gemini" -> "gemini-3.8-flash"; case "grok" -> "grok-4.7"; default -> "qwen3:8b"; };
    }
    private ChatRequest request(String name, String system, boolean schema, int timeout) {
        return new ChatRequest(system, List.of(new ChatMessage(ChatMessage.Role.USER, "Tēnā koutou"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "Previous speech"), new ChatMessage(ChatMessage.Role.USER, "Next turn")),
                new ModelConfig(name, model(name), null, null, 4096, timeout),
                schema ? new OutputSchema("result", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}") : null);
    }
    private String valid(String name) {
        return switch (name) {
            case "gemini" -> "{\"modelVersion\":\"returned-model\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"THOUGHT_SENTINEL\",\"thought\":true},{\"text\":\"Public words\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":3,\"thoughtsTokenCount\":1}}";
            case "anthropic" -> "{\"model\":\"returned-model\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"THOUGHT_SENTINEL\"},{\"type\":\"text\",\"text\":\"Public words\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}";
            case "ollama" -> "{\"model\":\"returned-model\",\"done\":true,\"done_reason\":\"stop\",\"message\":{\"content\":\"Public words\",\"thinking\":\"THOUGHT_SENTINEL\"},\"prompt_eval_count\":12,\"eval_count\":4}";
            default -> "{\"model\":\"returned-model\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Public words\",\"reasoning_content\":\"THOUGHT_SENTINEL\",\"tool_calls\":[]}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}";
        };
    }
    @ParameterizedTest @ValueSource(strings = {"gemini", "grok", "anthropic", "ollama"})
    void mapsIsolatedRequestsSchemasCredentialsAndUsage(String name) {
        response = valid(name);
        var adapter = provider(name);
        var first = adapter.complete(request(name, "FIRST_PRIVATE_SENTINEL", true, 10));
        adapter.complete(request(name, "SECOND_PRIVATE_SENTINEL", false, 10));
        assertEquals("Public words", first.requireCompletedText());
        assertEquals(name, first.provider());
        assertEquals("returned-model", first.model());
        assertEquals(new ChatResponse.TokenUsage(12, 4), first.usage());
        assertFalse(bodies.get(1).contains("FIRST_PRIVATE_SENTINEL"));
        assertFalse(bodies.get(1).contains("THOUGHT_SENTINEL"));
        assertFalse(bodies.get(0).contains("KEY_SENTINEL"));
        assertTrue(bodies.get(0).contains("Tēnā koutou"));
        JsonNode body = Json.read(bodies.get(0), JsonNode.class);
        switch (name) {
            case "gemini" -> {
                assertEquals("model", body.path("contents").get(1).path("role").asText());
                assertEquals("FIRST_PRIVATE_SENTINEL", body.path("systemInstruction").path("parts").get(0).path("text").asText());
                assertEquals(4096, body.path("generationConfig").path("maxOutputTokens").asInt());
                assertTrue(body.path("generationConfig").path("responseJsonSchema").isObject());
                assertEquals(List.of("KEY_SENTINEL"), headers.get(0).get("X-goog-api-key"));
            }
            case "grok" -> {
                assertEquals(4096, body.path("max_tokens").asInt());
                assertEquals("system", body.path("messages").get(0).path("role").asText());
                assertEquals("assistant", body.path("messages").get(2).path("role").asText());
                assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
                assertFalse(body.has("store"));
                assertFalse(body.has("reasoning_effort"));
                assertEquals(List.of("Bearer KEY_SENTINEL"), headers.get(0).get("Authorization"));
            }
            case "anthropic" -> {
                assertEquals("FIRST_PRIVATE_SENTINEL", body.path("system").asText());
                assertEquals(3, body.path("messages").size());
                assertEquals(4096, body.path("max_tokens").asInt());
                assertTrue(body.path("output_config").path("format").path("schema").isObject());
                assertEquals(List.of("KEY_SENTINEL"), headers.get(0).get("X-api-key"));
                assertEquals(List.of("2023-06-01"), headers.get(0).get("Anthropic-version"));
            }
            case "ollama" -> {
                assertFalse(body.path("stream").asBoolean());
                assertEquals(16384, body.path("options").path("num_ctx").asInt());
                assertEquals(4096, body.path("options").path("num_predict").asInt());
                assertTrue(body.path("format").isObject());
                assertFalse(headers.get(0).containsKey("Authorization"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"gemini", "grok", "anthropic", "ollama"})
    void rejectsTruncatedMalformedAndUntrustedFailures(String name) {
        response = valid(name).replace("\"STOP\"", "\"MAX_TOKENS\"").replace("\"end_turn\"", "\"max_tokens\"").replace("\"stop\"", "\"length\"");
        var adapter = provider(name);
        var truncated = adapter.complete(request(name, "private", false, 10));
        assertEquals(ChatResponse.CompletionStatus.TRUNCATED, truncated.status());
        assertThrows(IllegalStateException.class, truncated::requireCompletedText);
        for (String bad : List.of("{}", "SECRET_SENTINEL", "null")) {
            response = bad;
            var error = assertThrows(IllegalStateException.class, () -> adapter.complete(request(name, "private", false, 10)));
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("SECRET_SENTINEL"));
        }
        status = 401; response = "SECRET_SENTINEL";
        var error = assertThrows(IllegalStateException.class, () -> adapter.complete(request(name, "private", false, 10)));
        assertTrue(error.getMessage().contains("HTTP 401"));
        assertNull(error.getCause());
        assertFalse(adapter.toString().contains("KEY_SENTINEL"));
    }
    @ParameterizedTest @ValueSource(strings = {"gemini", "grok", "anthropic", "ollama"})
    void cancellationPreservesFlagAndAvoidsHttp(String name) {
        response = valid(name);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> provider(name).complete(request(name, "private", false, 10)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }
    @Test void retriesOnlyRateLimitsOnceAndHonorsTimeout() {
        response = valid("grok"); rateLimitFirst = true;
        assertEquals("Public words", provider("grok").complete(request("grok", "private", false, 10)).text());
        assertEquals(2, calls.get());
        rateLimitFirst = false; status = 429;
        assertThrows(IllegalStateException.class, () -> provider("grok").complete(request("grok", "private", false, 10)));
        assertEquals(4, calls.get());
        status = 500;
        assertThrows(IllegalStateException.class, () -> provider("grok").complete(request("grok", "private", false, 10)));
        assertEquals(5, calls.get());
        status = 200; delayMillis = 1500;
        var error = assertThrows(IllegalStateException.class, () -> provider("grok").complete(request("grok", "private", false, 1)));
        assertTrue(error.getMessage().contains("timed out"));
        assertEquals(6, calls.get());
    }
    @Test void refusalAndToolCallsNeverBecomeSpeech() {
        response = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        assertEquals(ChatResponse.CompletionStatus.REFUSED, provider("gemini").complete(request("gemini", "private", false, 10)).status());
        response = valid("anthropic").replace("end_turn", "refusal");
        assertEquals(ChatResponse.CompletionStatus.REFUSED, provider("anthropic").complete(request("anthropic", "private", false, 10)).status());
        response = valid("grok").replace("\"tool_calls\":[]", "\"tool_calls\":[{\"id\":\"call\"}]");
        assertEquals(ChatResponse.CompletionStatus.UNSUPPORTED, provider("grok").complete(request("grok", "private", false, 10)).status());
    }
    @Test void localContextAndCapabilityFailuresHappenBeforeAnyCall() {
        var small = new OllamaChatManager(HttpClient.newHttpClient(), base, 1024);
        assertThrows(IllegalArgumentException.class, () -> small.complete(request("ollama", "private", false, 10)));
        assertThrows(IllegalArgumentException.class, () -> provider("grok").complete(request("gemini", "private", false, 10)));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("grok", "grok-4.7", null, "medium", 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("gemini", "gemini/../secret", null, null, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("anthropic", "claude-sonnet-4-6", 1.5, null, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatManager(URI.create("https://example.org"), 16384));
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatManager(URI.create("http:/broken"), 16384));
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatManager(URI.create("http://127.0.0.1:11434/path"), 16384));
        assertEquals(0, calls.get());
    }
}
