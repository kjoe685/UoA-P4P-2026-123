package engine.evaluation.local;

import com.sun.net.httpserver.HttpServer;
import engine.evaluation.EvaluationCoordinator;
import engine.evaluation.EvaluationStatus;
import engine.evaluation.Evaluator;
import engine.transcript.DebateEvent;
import engine.transcript.EventType;
import engine.transcript.Participant;
import engine.transcript.Transcript;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalNlpEvaluatorTest {
    private static final String VADER = "vader-sentiment";
    private static final String STANCE = "deberta-stance";
    private static final Participant SPEAKER = new Participant("PRIVATE_ID_SENTINEL", "NAME_SENTINEL", "PARTY_SENTINEL");

    static Transcript transcript() {
        return new Transcript(List.of(
                new DebateEvent(1, 0, "Housing", 0, EventType.TOPIC_ANNOUNCEMENT, null, "ANNOUNCEMENT_SENTINEL"),
                new DebateEvent(2, 0, "Housing", 1, EventType.SPEECH, SPEAKER, "Excellent homes 😀!"),
                new DebateEvent(3, 0, "Housing", 1, EventType.INTERJECTION, SPEAKER, "Good idea!"),
                new DebateEvent(4, 1, "Climate", 0, EventType.TOPIC_ANNOUNCEMENT, null, "Another topic"),
                new DebateEvent(5, 1, "Climate", 1, EventType.SPEECH, SPEAKER, "Good climate policy!")));
    }

    static LocalEvaluationConfig config(URI endpoint, int batchSize) {
        return new LocalEvaluationConfig(1, endpoint, 10, batchSize, List.of(VADER, STANCE),
                List.of(new LocalEvaluationConfig.PolicyTarget("homes", 0, "Build more public housing")));
    }

    static NlpResponse valid(NlpRequest request) {
        var provenance = new NlpResponse.Provenance("vaderSentiment", "3.3.2", "0.1.0", "a".repeat(64), "cpu",
                Map.of("vaderSentiment", "3.3.2"), "lexical proportions; compound [-1,1]", "unicode-code-points", Map.of());
        var items = request.turns().stream().map(turn -> new NlpResponse.Item(turn.turnId(), null, "ok", null,
                List.of(new NlpResponse.Chunk(0, 0, turn.text().codePointCount(0, turn.text().length()), turn.text(), "positive",
                        Map.of("negative", 0.0, "neutral", .3, "positive", .7), .7, null)), 1)).toList();
        return new NlpResponse(1, List.of(new NlpResponse.Method(request.methods().get(0), "ok", null, provenance, items, 2)));
    }

    @Test void mappingExcludesIdentitiesAnnouncementsAndTopicsAndSelectsTargetsByTopic() {
        var config = config(URI.create("http://127.0.0.1:8765/v1/analyze"), 2);
        var requests = NlpInputMapper.batches(transcript(), STANCE, config);
        assertEquals(2, requests.size());
        assertEquals(List.of(2L, 3L), requests.get(0).turns().stream().map(NlpRequest.Turn::turnId).toList());
        assertEquals("homes", requests.get(0).turns().get(0).targets().get(0).id());
        assertTrue(requests.get(1).turns().get(0).targets().isEmpty());
        String payload = Json.write(requests);
        for (String secret : List.of("PRIVATE_ID_SENTINEL", "NAME_SENTINEL", "PARTY_SENTINEL", "ANNOUNCEMENT_SENTINEL", "Climate")) {
            assertFalse(payload.contains(secret));
        }
        assertTrue(NlpInputMapper.batches(transcript(), VADER, config).get(0).turns().get(0).targets().isEmpty());
    }

    @Test void httpRoundTripPreservesUnicodeEvidenceAndHasNoHistoryOrAgentMetadata() throws Exception {
        var bodies = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/analyze", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                byte[] response = Json.write(valid(Json.read(body, NlpRequest.class))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var config = config(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/analyze"), 2);
            var evaluator = new LocalNlpEvaluator(VADER, new HttpNlpClient(config.endpoint(), config.timeout()), config);
            var result = evaluator.evaluate(transcript());
            assertEquals(EvaluationStatus.OK, result.getStatus());
            var metric = (LocalAnalysisMetric) result.getMetrics().get("analysis");
            assertEquals(2, metric.batches().size());
            assertEquals("Excellent homes 😀!", metric.batches().get(0).items().get(0).chunks().get(0).text());
            assertFalse(bodies.get(1).contains("Excellent homes"));
            assertFalse(Json.write(bodies).contains("SENTINEL"));
            assertThrows(UnsupportedOperationException.class, () -> metric.batches().clear());
        } finally { server.stop(0); }
    }

    @Test void failedMethodAndFailedBatchDoNotEraseOtherResultsOrAlterTranscript() {
        var config = config(URI.create("http://127.0.0.1:8765/v1/analyze"), 1);
        NlpClient partial = request -> {
            if (request.turns().get(0).turnId() == 3) throw new IllegalStateException("PRIVATE_SENTINEL");
            return valid(request);
        };
        var input = transcript();
        String before = Json.write(input);
        Map<String, Evaluator> methods = new LinkedHashMap<>();
        methods.put("broken", ignored -> { throw new IllegalStateException("PRIVATE_SENTINEL"); });
        methods.put(VADER, new LocalNlpEvaluator(VADER, partial, config));
        var report = new EvaluationCoordinator(methods).evaluate(input);
        assertEquals(2, report.size());
        assertEquals(EvaluationStatus.FAILED, report.get(0).getStatus());
        assertEquals(EvaluationStatus.FAILED, report.get(1).getStatus());
        var batches = ((LocalAnalysisMetric) report.get(1).getMetrics().get("analysis")).batches();
        assertEquals(List.of("ok", "failed", "ok"), batches.stream().map(NlpResponse.Method::status).toList());
        assertEquals(3, batches.get(1).items().get(0).turnId());
        assertFalse(Json.write(report).contains("PRIVATE_SENTINEL"));
        assertEquals(before, Json.write(input));
    }

    @Test void emptyTranscriptDoesNotContactServiceAndInvalidEvidenceFailsClosed() {
        var config = config(URI.create("http://127.0.0.1:8765/v1/analyze"), 10);
        var empty = new LocalNlpEvaluator(VADER, request -> { fail("Must not contact service"); return null; }, config);
        assertEquals(EvaluationStatus.INSUFFICIENT_EVIDENCE, empty.evaluate(Transcript.empty()).getStatus());
        var request = NlpInputMapper.batches(transcript(), VADER, config).get(0);
        String good = Json.write(valid(request));
        for (String invalid : List.of(good.replace("\"turnId\":2", "\"turnId\":999"),
                good.replace("\"end\":18", "\"end\":1"), good.replace("\"positive\":0.7", "\"positive\":1.7"),
                good.replace("Excellent homes", "Altered evidence"))) {
            assertNotEquals(good, invalid);
            var response = Json.read(invalid, NlpResponse.class);
            assertThrows(RuntimeException.class, () -> NlpResponseValidator.validate(request, response));
        }
        var omitted = new NlpResponse(1, List.of(new NlpResponse.Method(VADER, "ok", null,
                valid(request).methods().get(0).provenance(), List.of(), 0)));
        assertThrows(IllegalStateException.class, () -> NlpResponseValidator.validate(request, omitted));
    }

    @Test void errorsAreSanitizedAndInterruptsRetainTheFlag() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "PRIVATE_SENTINEL".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var client = new HttpNlpClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(10));
            var request = new NlpRequest(1, List.of(VADER), List.of());
            var error = assertThrows(IllegalStateException.class, () -> client.analyze(request));
            assertEquals("Local NLP request failed (HTTP 503)", error.getMessage());
            assertNull(error.getCause());
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class, () -> client.analyze(request));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        } finally { server.stop(0); }
    }

    @Test void evidenceOffsetsUseCodePointsAndPythonWhitespaceRules() {
        var turn = new NlpRequest.Turn(1, "\u00a0Good 😀!\u0085", List.of());
        var request = new NlpRequest(1, List.of(VADER), List.of(turn));
        var chunk = new NlpResponse.Chunk(0, 1, 8, "Good 😀!", "positive",
                Map.of("negative", 0.0, "neutral", .3, "positive", .7), .7, null);
        var item = new NlpResponse.Item(1, null, "ok", null, List.of(chunk), 1);
        var response = new NlpResponse(1, List.of(new NlpResponse.Method(VADER, "ok", null,
                valid(request).methods().get(0).provenance(), List.of(item), 1)));
        assertDoesNotThrow(() -> NlpResponseValidator.validate(request, response));
    }
}
