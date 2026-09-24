package engine.evaluation.local;

import com.sun.net.httpserver.HttpServer;
import engine.evaluation.EvaluationCommand;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationCommandTest {
    @TempDir Path directory;

    @Test void cliEvaluatesSavedEvidenceAndProtectsInputs() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/analyze", exchange -> {
            try (exchange) {
                var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), NlpRequest.class);
                byte[] bytes = Json.write(LocalNlpEvaluatorTest.valid(request)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            Path input = directory.resolve("transcript.json");
            Path configPath = directory.resolve("config.json");
            Path output = directory.resolve("evaluation.json");
            String evidence = Json.write(LocalNlpEvaluatorTest.transcript());
            Files.writeString(input, evidence);
            var config = LocalNlpEvaluatorTest.config(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/analyze"), 2);
            Files.writeString(configPath, Json.write(config));
            var out = new PrintStream(new ByteArrayOutputStream());
            assertEquals(0, EvaluationCommand.execute(new String[]{"--input", input.toString(), "--config", configPath.toString(),
                    "--output", output.toString(), "--methods", "vader-sentiment"}, out));
            var result = (java.util.Map<?, ?>) Json.parse(Files.readString(output));
            assertEquals(64, ((String) result.get("transcriptSha256")).length());
            assertEquals(64, ((String) result.get("configurationSha256")).length());
            assertEquals(1, ((List<?>) result.get("methods")).size());
            assertEquals(evidence, Files.readString(input));
            assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{
                    "--input", input.toString(), "--config", configPath.toString(), "--output", input.toString()}, out));
            assertEquals(evidence, Files.readString(input));
        } finally { server.stop(0); }
    }

    @Test void rejectsDuplicateMethodsTargetsAndUnknownTopicMappingsBeforeContactingService() throws Exception {
        var original = LocalNlpEvaluatorTest.config(URI.create("http://127.0.0.1:1/v1/analyze"), 2);
        assertThrows(IllegalArgumentException.class, () -> new LocalEvaluationConfig(1, original.endpoint(), 10, 2,
                List.of("vader-sentiment", "vader-sentiment"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LocalEvaluationConfig(1, original.endpoint(), 10, 2,
                original.methods(), List.of(original.policyTargets().get(0), original.policyTargets().get(0))));
        var bad = new LocalEvaluationConfig(1, original.endpoint(), 10, 2, original.methods(),
                List.of(new LocalEvaluationConfig.PolicyTarget("absent", 999, "A proposition")));
        Path input = directory.resolve("input.json");
        Path config = directory.resolve("config.json");
        Path output = directory.resolve("output.json");
        Files.writeString(input, Json.write(LocalNlpEvaluatorTest.transcript()));
        Files.writeString(config, Json.write(bad));
        assertThrows(IllegalArgumentException.class, () -> EvaluationCommand.execute(new String[]{
                "--input", input.toString(), "--config", config.toString(), "--output", output.toString()}, System.out));
        assertFalse(Files.exists(output));
    }
}
