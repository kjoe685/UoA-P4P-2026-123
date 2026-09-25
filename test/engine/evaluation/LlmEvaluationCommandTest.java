package engine.evaluation;

import engine.TestFixtures;
import engine.utils.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LlmEvaluationCommandTest {
    @TempDir Path directory;
    @Test void savedTranscriptUsesIndependentModelAndNeedsNoLocalServiceOrDebatePrompts() throws Exception {
        Path input = directory.resolve("input.json"), output = directory.resolve("output.json");
        Files.writeString(input, Json.write(LlmFixtures.transcript()));
        var calls = new AtomicInteger();
        int status = EvaluationCommand.execute(new String[]{"--input", input.toString(), "--output", output.toString(),
                "--methods", "llm-rubric", "--model", "gemini-flash", "--config", directory.resolve("absent.json").toString()},
                new PrintStream(new ByteArrayOutputStream()), model -> {
                    assertEquals("gemini", model.provider());
                    calls.incrementAndGet();
                    return request -> TestFixtures.response(LlmFixtures.valid(request));
                });
        assertEquals(0, status);
        assertEquals(2, calls.get());
        JsonNode report = Json.read(Files.readString(output), JsonNode.class);
        assertEquals(1, report.path("methods").size());
        assertEquals("OK", report.path("methods").get(0).path("status").asText());
        assertEquals(64, report.path("configurationSha256").asText().length());
        assertEquals("gemini", report.path("methods").get(0).path("metrics").path("assessments").path("model").path("provider").asText());
    }
    @Test void failedProviderStillWritesReportAndReturnsFailureStatus() throws Exception {
        Path input = directory.resolve("input.json"), output = directory.resolve("output.json");
        Files.writeString(input, Json.write(LlmFixtures.transcript()));
        assertEquals(1, EvaluationCommand.execute(new String[]{"--input", input.toString(), "--output", output.toString(), "--methods", "llm-rubric"},
                new PrintStream(new ByteArrayOutputStream()), model -> { throw new IllegalArgumentException("KEY_PRIVATE_SENTINEL"); }));
        String report = Files.readString(output);
        assertTrue(report.contains("provider_failed"));
        assertFalse(report.contains("KEY_PRIVATE_SENTINEL"));
    }

    @Test void ownerAssignmentsAreJoinedOnlyAfterEvaluationAndNeverReachProviders() throws Exception {
        Path input = directory.resolve("input.json"), output = directory.resolve("owner.json"), assignments = directory.resolve("assignments.json");
        Files.writeString(input, Json.write(LlmFixtures.transcript()));
        Files.writeString(assignments, "{\"schemaVersion\":1,\"assignments\":{\"A\":\"TOPIC_DERAILMENT\",\"B\":\"NONE\"}}");
        var calls = new AtomicInteger();
        var out = new PrintStream(new ByteArrayOutputStream());
        java.util.function.Function<engine.config.ModelConfig, engine.ChatManager> providers = model -> request -> {
            calls.incrementAndGet();
            String payload = Json.write(request);
            assertFalse(payload.contains("TOPIC_DERAILMENT"));
            assertFalse(payload.contains("assignedStrategy"));
            return TestFixtures.response(LlmFixtures.valid(request));
        };
        assertEquals(0, EvaluationCommand.execute(new String[]{"--input", input.toString(), "--output", output.toString(),
                "--methods", "llm-rubric", "--assignments", assignments.toString()}, out, providers));
        JsonNode report = Json.read(Files.readString(output), JsonNode.class);
        assertEquals(2, calls.get());
        assertEquals(4, report.path("assignmentComparison").size());
        assertEquals("TOPIC_DERAILMENT", report.path("assignmentComparison").get(0).path("assignedStrategy").asText());
        assertEquals("rhetorical_tactics", report.path("assignmentComparison").get(0).path("observedRhetoric").path("metricId").asText());
        assertFalse(Json.write(report.path("evaluation")).contains("TOPIC_DERAILMENT"));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{"--input", input.toString(),
                "--output", assignments.toString(), "--methods", "llm-rubric", "--assignments", assignments.toString()}, out, providers));
        Files.writeString(assignments, "{\"schemaVersion\":1,\"assignments\":{\"ABSENT\":\"NONE\"}}");
        assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{"--input", input.toString(),
                "--output", output.toString(), "--methods", "llm-rubric", "--assignments", assignments.toString()}, out, providers));
        assertEquals(2, calls.get());
    }
    @Test void preflightAndBadArgumentsDoNotReadCredentialsOrContactProviders() throws Exception {
        var calls = new AtomicInteger();
        java.util.function.Function<engine.config.ModelConfig, engine.ChatManager> providers = model -> { calls.incrementAndGet(); throw new AssertionError(); };
        var out = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, EvaluationCommand.execute(new String[]{"--validate-config", "--methods", "llm-rubric", "--model", "grok"}, out, providers));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{"--validate-config", "--methods", "llm-rubric,llm-rubric"}, out, providers));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{"--validate-config", "--methods", "llm-rubric", "--model", "unknown"}, out, providers));
        Path input = directory.resolve("input.json");
        Files.writeString(input, Json.write(LlmFixtures.transcript()));
        for (String protectedFile : new String[]{input.toString(), "resources/config/engine.json", "resources/config/llm-evaluation.json", "resources/prompts/EvaluatorRepair.txt", "resources/evaluation/rubric.json"}) {
            assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{"--input", input.toString(),
                    "--output", protectedFile, "--methods", "llm-rubric"}, out, providers));
        }
        assertEquals(0, calls.get());
    }
}
