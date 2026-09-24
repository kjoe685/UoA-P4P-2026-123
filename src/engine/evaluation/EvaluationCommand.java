package engine.evaluation;

import engine.evaluation.local.HttpNlpClient;
import engine.evaluation.local.LocalEvaluationConfig;
import engine.evaluation.local.LocalNlpEvaluator;
import engine.transcript.Transcript;
import engine.utils.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline transcript evaluation frontend. Never loads debate prompts or API credentials. */
public final class EvaluationCommand {
    private EvaluationCommand() { }

    public static int execute(String[] args, PrintStream out) {
        Path input = null;
        Path output = null;
        Path configPath = Path.of("resources/config/evaluation.json");
        List<String> selected = null;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (option.equals("--help")) {
                out.println("Usage: java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate"
                        + " --input debate.json --output evaluation.json [--config FILE] [--methods vader-sentiment,cardiff-sentiment,deberta-stance]");
                return 0;
            }
            if (!List.of("--input", "--output", "--config", "--methods").contains(option) || ++index == args.length)
                throw new IllegalArgumentException("Invalid evaluation arguments; use evaluate --help");
            switch (option) {
                case "--input" -> input = Path.of(args[index]);
                case "--output" -> output = Path.of(args[index]);
                case "--config" -> configPath = Path.of(args[index]);
                case "--methods" -> selected = Arrays.stream(args[index].split(",", -1)).map(String::trim).toList();
                default -> throw new IllegalArgumentException("Unknown evaluation argument");
            }
        }
        if (input == null || output == null) throw new IllegalArgumentException("Evaluation requires --input and --output");
        try {
            if (sameFile(input, output) || sameFile(configPath, output))
                throw new IllegalArgumentException("Evaluation output must not overwrite its transcript or configuration");
            var config = LocalEvaluationConfig.load(configPath);
            if (selected != null) config = new LocalEvaluationConfig(config.schemaVersion(), config.endpoint(), config.timeoutSeconds(),
                    config.batchSize(), selected, config.policyTargets());
            byte[] evidence = Files.readAllBytes(input);
            Transcript transcript = Json.read(new String(evidence, StandardCharsets.UTF_8), Transcript.class);
            for (var target : config.policyTargets()) {
                if (transcript.events().stream().noneMatch(event -> event.topicIndex() == target.topicIndex()))
                    throw new IllegalArgumentException("A policy target refers to a topic absent from this transcript");
            }
            var client = new HttpNlpClient(config.endpoint(), config.timeout());
            Map<String, Evaluator> evaluators = new LinkedHashMap<>();
            for (String method : config.methods()) evaluators.put(method, new LocalNlpEvaluator(method, client, config));
            var results = new EvaluationCoordinator(evaluators).evaluate(transcript);
            var report = new EvaluationReport(1, sha256(evidence), sha256(Json.write(config).getBytes(StandardCharsets.UTF_8)),
                    config.policyTargets(), results);
            Files.writeString(output, Json.write(report));
            results.forEach(result -> out.println(result.getEvaluatorId() + ": " + result.getStatus()));
            out.println("Separate evaluation results saved to " + output);
            return results.stream().anyMatch(result -> result.getStatus() == EvaluationStatus.FAILED) ? 1 : 0;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the transcript or write the evaluation report");
        }
    }

    private static boolean sameFile(Path first, Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || Files.exists(second) && Files.isSameFile(first, second);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
