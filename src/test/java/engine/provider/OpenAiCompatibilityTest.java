package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import engine.chat.*;
import engine.config.ModelConfig;
import engine.utils.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibilityTest {
    private static final OutputSchema SCHEMA = new OutputSchema("result",
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}");
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private OpenAIChatManager adapter;
    private volatile String response = "{\"model\":\"returned-model\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Public words\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                bodies.add(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class));
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        adapter = new OpenAIChatManager("KEY_SENTINEL", ProviderHttp.client(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }
    @AfterEach void stop() { server.stop(0); }
    private ChatRequest request(ModelConfig model, boolean schema) {
        return new ChatRequest("Private instructions", List.of(new ChatMessage(ChatMessage.Role.USER, "Tēnā koutou")),
                model, schema ? SCHEMA : null);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"none", "low", "medium", "high", "xhigh", "max"})
    void lunaSupportsDocumentedEffortsAndNativeSchemasWithoutChangingTransport(String effort) {
        Double temperature = "none".equals(effort) ? .6 : null;
        var model = new ModelConfig("openai", "gpt-6-luna", temperature, effort, 4096, 5);
        assertTrue(ProviderCapabilities.structuredOutput(model));
        var result = adapter.complete(request(model, true));
        adapter.complete(request(model, false));
        assertEquals("Public words", result.requireCompletedText());
        assertEquals("openai", result.provider());
        assertEquals("returned-model", result.model());
        assertEquals(new ChatResponse.TokenUsage(12, 4), result.usage());
        for (var body : bodies) {
            assertEquals("gpt-6-luna", body.path("model").asText());
            assertEquals(4096, body.path("max_completion_tokens").asInt());
            assertFalse(body.has("max_tokens"));
            assertTrue(body.path("store").isBoolean()); assertFalse(body.path("store").asBoolean());
            assertEquals(effort != null, body.has("reasoning_effort"));
            if (effort != null) assertEquals(effort, body.path("reasoning_effort").asText());
            assertEquals(temperature != null, body.has("temperature"));
            if (temperature != null) assertEquals(temperature, body.path("temperature").asDouble());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("Tēnā koutou", body.path("messages").get(1).path("content").asText());
            assertFalse(body.toString().contains("KEY_SENTINEL"));
        }
        var format = bodies.get(0).path("response_format");
        assertEquals("json_schema", format.path("type").asText());
        assertEquals("result", format.path("json_schema").path("name").asText());
        assertTrue(format.path("json_schema").path("strict").asBoolean());
        assertEquals(Json.read(SCHEMA.json(), JsonNode.class), format.path("json_schema").path("schema"));
        assertFalse(bodies.get(1).has("response_format"));
        assertEquals(List.of("Bearer KEY_SENTINEL", "Bearer KEY_SENTINEL"), authorization);
    }

    @Test void lunaRejectsUnsupportedSamplingAndEffortBeforeHttp() {
        for (String effort : new String[]{null, "low", "medium", "high", "xhigh", "max", "minimal"})
            assertThrows(IllegalArgumentException.class, () -> new ModelConfig("openai", "gpt-6-luna", 1d, effort, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("openai", "gpt-6-luna", null, "minimal", 100, 5));
        for (double temperature : new double[]{0, 2})
            assertDoesNotThrow(() -> new ModelConfig("openai", "gpt-6-luna", temperature, "none", 100, 5));
        assertEquals(0, bodies.size());
    }

    @ParameterizedTest @CsvSource({
            "gpt-4o-mini,1,,true", "gpt-4o-2024-08-06,0,,true", "gpt-3.5-turbo,0.7,,false",
            "gpt-5-nano,,minimal,true", "gpt-5-nano,,medium,true", "gpt-5.4-nano,,high,true",
            "gpt-5.6-luna,,low,true", "o3-mini,,low,true", "o4-mini,,high,true", "o1,,,true", "o1-mini,,,false"})
    void olderModelsKeepTheirExistingOptionsAndWireFormat(String name, Double temperature, String effort, boolean schema) {
        var model = new ModelConfig("openai", name, temperature, effort, 1024, 5);
        assertEquals(schema, ProviderCapabilities.structuredOutput(model));
        assertEquals("Public words", adapter.complete(request(model, schema)).requireCompletedText());
        var body = bodies.get(0);
        assertEquals(name, body.path("model").asText());
        assertEquals(1024, body.path("max_completion_tokens").asInt());
        assertFalse(body.has("max_tokens")); assertFalse(body.path("store").asBoolean());
        assertEquals(temperature != null, body.has("temperature"));
        if (temperature != null) assertEquals(temperature, body.path("temperature").asDouble());
        assertEquals(effort != null, body.has("reasoning_effort"));
        if (effort != null) assertEquals(effort, body.path("reasoning_effort").asText());
        assertEquals(schema, body.has("response_format"));
        if (schema) assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
    }

    @Test void lunaOptionsDoNotLeakIntoOlderModelsOtherProvidersOrUnverifiedModelIds() {
        for (String name : List.of("gpt-4o-mini", "gpt-5-nano", "gpt-5.6-luna", "o3-mini", "o4-mini",
                "gpt-6-luna-unknown", "gpt-6-sol", "gpt-6-astra")) {
            for (String effort : List.of("none", "xhigh", "max"))
                assertThrows(IllegalArgumentException.class, () -> new ModelConfig("openai", name, null, effort, 100, 5));
        }
        for (String name : List.of("gpt-6-luna-unknown", "gpt-6-sol", "gpt-6-astra"))
            assertFalse(ProviderCapabilities.structuredOutput(new ModelConfig("openai", name, null, null, 100, 5)));
        for (String name : List.of("gpt-5-nano", "o1", "o3-mini", "o4-mini"))
            assertThrows(IllegalArgumentException.class, () -> new ModelConfig("openai", name, .5, null, 100, 5));
        for (String name : List.of("gpt-4o-mini", "o3-mini", "o4-mini"))
            assertThrows(IllegalArgumentException.class, () -> new ModelConfig("openai", name, null, "minimal", 100, 5));
        for (String provider : List.of("anthropic", "gemini", "grok", "ollama", "demo"))
            assertThrows(IllegalArgumentException.class, () -> new ModelConfig(provider, "gpt-6-luna", null, "none", 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new ModelConfig("grok", "grok-3-mini", .5, "low", 100, 5));
        assertDoesNotThrow(() -> new ModelConfig("grok", "grok-3-mini", null, "low", 100, 5));
        assertDoesNotThrow(() -> new ModelConfig("grok", "grok-3-mini", null, "high", 100, 5));
        assertEquals(0, bodies.size());
    }

    @Test void lunaTruncationStillCannotBecomePublicSpeech() {
        response = response.replace("\"stop\"", "\"length\"");
        var model = new ModelConfig("openai", "gpt-6-luna", null, "none", 256, 5);
        var result = adapter.complete(request(model, false));
        assertEquals(ChatResponse.CompletionStatus.TRUNCATED, result.status());
        assertThrows(IllegalStateException.class, result::requireCompletedText);
    }
}
