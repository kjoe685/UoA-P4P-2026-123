package engine.provider;

import com.sun.net.httpserver.HttpServer;
import engine.TestFixtures;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.config.ModelConfig;
import engine.utils.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class OpenAIChatManagerTest {
    private HttpServer server;
    private OpenAIChatManager provider;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile String response = "{\"model\":\"returned-model\",\"choices\":[{\"finish_reason\":\"stop\","
            + "\"message\":{\"content\":\"Public words\",\"private_state\":\"PRIVATE_STATE_SENTINEL\"}}],"
            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}";
    private volatile int status = 200;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try (exchange) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        provider = new OpenAIChatManager("TEST_CREDENTIAL_SENTINEL", HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"));
    }

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    private ChatRequest request(String system) {
        return new ChatRequest(system, List.of(new ChatMessage(ChatMessage.Role.USER, "Tēnā koutou, \"House\"")), TestFixtures.model());
    }

    @Test void mapsRolesLimitsUnicodeAndUsageWithoutKeepingHistory() {
        var first = provider.complete(request("FIRST_PRIVATE_SENTINEL"));
        var second = provider.complete(new ChatRequest("SECOND_PRIVATE_SENTINEL", List.of(
                new ChatMessage(ChatMessage.Role.ASSISTANT, "Earlier public speech"),
                new ChatMessage(ChatMessage.Role.USER, "Next turn")), TestFixtures.model()));
        assertEquals("Public words", first.requireCompletedText());
        assertEquals(new ChatResponse.TokenUsage(12, 4), first.usage());
        assertEquals("returned-model", second.model());
        assertTrue(first.latencyMillis() >= 0);
        Map<?, ?> body = (Map<?, ?>) Json.parse(bodies.get(0));
        assertEquals(4096, body.get("max_completion_tokens"));
        assertEquals("medium", body.get("reasoning_effort"));
        assertEquals(false, body.get("store"));
        assertFalse(body.containsKey("temperature"));
        var messages = (List<?>) body.get("messages");
        assertEquals("system", ((Map<?, ?>) messages.get(0)).get("role"));
        assertEquals("Tēnā koutou, \"House\"", ((Map<?, ?>) messages.get(1)).get("content"));
        assertFalse(bodies.get(1).contains("FIRST_PRIVATE_SENTINEL"));
        assertFalse(bodies.get(1).contains("Public words"));
        assertFalse(bodies.get(1).contains("PRIVATE_STATE_SENTINEL"));
        assertFalse(bodies.get(0).contains("TEST_CREDENTIAL_SENTINEL"));
        assertEquals("Bearer TEST_CREDENTIAL_SENTINEL", authorizations.get(0));
        var secondMessages = (List<?>) ((Map<?, ?>) Json.parse(bodies.get(1))).get("messages");
        assertEquals("assistant", ((Map<?, ?>) secondMessages.get(1)).get("role"));
    }

    @Test void sendsTemperaturePresetWithoutReasoningEffort() {
        provider.complete(new ChatRequest("Private", List.of(),
                new ModelConfig("openai", "gpt-4o-mini", 1.0, null, 100, 10)));
        Map<?, ?> body = (Map<?, ?>) Json.parse(bodies.get(0));
        assertEquals(1.0, body.get("temperature"));
        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test void structuredOutputsAreOptInAndKeepAnImmutableSchema() {
        var schema = new engine.chat.OutputSchema("assessment", "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
        provider.complete(new ChatRequest("Private", List.of(), TestFixtures.model(), schema));
        var body = Json.read(bodies.get(0), com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
        ((Map<?, ?>) schema.value()).clear();
        assertTrue(schema.json().contains("additionalProperties"));
        provider.complete(request("ordinary"));
        assertFalse(Json.read(bodies.get(1), com.fasterxml.jackson.databind.JsonNode.class).has("response_format"));
    }

    @Test void errorsNeverEchoResponseBodiesCredentialsOrPrompts() {
        status = 429;
        response = "TEST_CREDENTIAL_SENTINEL FIRST_PRIVATE_SENTINEL";
        var exception = assertThrows(IllegalStateException.class, () -> provider.complete(request("FIRST_PRIVATE_SENTINEL")));
        assertEquals("OpenAI request failed (HTTP 429)", exception.getMessage());
        assertNull(exception.getCause());
        assertFalse(provider.toString().contains("TEST_CREDENTIAL_SENTINEL"));
    }

    @ParameterizedTest @ValueSource(strings = {"not JSON PRIVATE_SENTINEL", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}"})
    void rejectsMalformedResponsesWithoutLeakingTheirContent(String invalid) {
        response = invalid;
        var exception = assertThrows(IllegalStateException.class, () -> provider.complete(request("private")));
        assertEquals("OpenAI returned an invalid chat response", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test void refusalAndTruncationAreExplicitAndCannotBecomeSpeech() {
        response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}";
        var truncated = provider.complete(request("private"));
        assertEquals(ChatResponse.CompletionStatus.TRUNCATED, truncated.status());
        assertNull(truncated.usage());
        assertThrows(IllegalStateException.class, truncated::requireCompletedText);
        response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"reason\"}}]}";
        var refusal = provider.complete(request("private"));
        assertEquals(ChatResponse.CompletionStatus.REFUSED, refusal.status());
        assertThrows(IllegalStateException.class, refusal::requireCompletedText);
    }

    @Test void interruptionRestoresTheThreadFlag() {
        Thread.currentThread().interrupt();
        try {
            var error = assertThrows(IllegalStateException.class, () -> provider.complete(request("private")));
            assertEquals("OpenAI request interrupted", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void invalidCredentialsFailWithoutEchoingTheirValue() {
        for (String key : List.of("SECRET_SENTINEL\n", "SECRET_SENTINEL\t", "SECRET_SENTINEL\u0001", "SECRET_SENTINEL ")) {
            var error = assertThrows(IllegalArgumentException.class, () -> new OpenAIChatManager(key));
            assertEquals("A valid API key is required", error.getMessage());
            assertNull(error.getCause());
        }
    }
}
