package engine.evaluation;

import engine.ChatManager;
import engine.config.EngineConfig;
import engine.config.ModelConfig;
import engine.evaluation.llm.LlmEvaluationResources;
import engine.evaluation.local.HttpNlpClient;
import engine.evaluation.local.LocalEvaluationConfig;
import engine.evaluation.local.LocalNlpEvaluator;
import engine.provider.ProviderFactory;
import engine.transcript.Transcript;
import engine.utils.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Saved-transcript evaluation. Only selected methods load their settings and credentials. */
public final class EvaluationCommand {
    private EvaluationCommand() { }
    public static int execute(String[] args, PrintStream out) {
        var providers = new ProviderFactory();
        return execute(args, out, providers::forModel);
    }
    // Injection seam keeps CLI integration tests offline.
    static int execute(String[] args, PrintStream out, Function<ModelConfig, ChatManager> providers) {
        Path input = null, output = null, configPath = null, resources = Path.of("resources");
        Path assignmentsPath = null;
        List<String> selected = null;
        String modelPreset = null;
        boolean validate = false;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (option.equals("--help")) {
                out.println("Usage: java -jar target/virtual-parliament-0.1.0-SNAPSHOT.jar evaluate"
                        + " --input debate.json --output evaluation.json [--resources DIR] [--config FILE]"
                        + " [--methods vader-sentiment,cardiff-sentiment,deberta-stance,llm-rubric] [--model PRESET]"
                        + " [--assignments PRIVATE_FILE] [--validate-config]");
                return 0;
            }
            if (option.equals("--validate-config")) { validate = true; continue; }
            if (!List.of("--input", "--output", "--config", "--methods", "--resources", "--model", "--assignments").contains(option) || ++index == args.length)
                throw new IllegalArgumentException("Invalid evaluation arguments; use evaluate --help");
            switch (option) {
                case "--input" -> input = Path.of(args[index]);
                case "--output" -> output = Path.of(args[index]);
                case "--config" -> configPath = Path.of(args[index]);
                case "--resources" -> resources = Path.of(args[index]);
                case "--model" -> modelPreset = args[index];
                case "--assignments" -> assignmentsPath = Path.of(args[index]);
                case "--methods" -> selected = Arrays.stream(args[index].split(",", -1)).map(String::trim).toList();
                default -> throw new IllegalArgumentException("Unknown evaluation argument");
            }
        }
        if (!validate && (input == null || output == null)) throw new IllegalArgumentException("Evaluation requires --input and --output");
        if (configPath == null) configPath = resources.resolve("config/evaluation.json");
        try {
            LocalEvaluationConfig local = null;
            if (selected == null) { local = LocalEvaluationConfig.load(configPath); selected = local.methods(); }
            var allowed = new HashSet<>(LocalEvaluationConfig.METHODS); allowed.add(LLMEvaluator.ID);
            if (selected.isEmpty() || !allowed.containsAll(selected) || new HashSet<>(selected).size() != selected.size())
                throw new IllegalArgumentException("Select unique, supported evaluation methods");
            boolean useLlm = selected.contains(LLMEvaluator.ID);
            if (modelPreset != null && !useLlm) throw new IllegalArgumentException("--model requires the llm-rubric method");
            if (assignmentsPath != null && !useLlm) throw new IllegalArgumentException("--assignments requires the llm-rubric method");
            List<String> localMethods = selected.stream().filter(LocalEvaluationConfig.METHODS::contains).toList();
            Map<String, Object> provenance = new LinkedHashMap<>();
            if (!localMethods.isEmpty()) {
                if (local == null) local = LocalEvaluationConfig.load(configPath);
                local = new LocalEvaluationConfig(local.schemaVersion(), local.endpoint(), local.timeoutSeconds(), local.batchSize(), localMethods, local.policyTargets());
                provenance.put("local", local);
            }
            LlmEvaluationResources llm = null;
            ModelConfig model = null;
            List<Path> protectedPaths = new ArrayList<>(List.of(configPath));
            if (input != null) protectedPaths.add(input);
            OwnerEvaluationReport.Assignments assignments = null;
            String assignmentsHash = null;
            if (assignmentsPath != null) {
                byte[] bytes = Files.readAllBytes(assignmentsPath);
                assignments = Json.read(new String(bytes, StandardCharsets.UTF_8), OwnerEvaluationReport.Assignments.class);
                assignmentsHash = LlmEvaluationResources.sha256(bytes);
                protectedPaths.add(assignmentsPath);
            }
            if (useLlm) {
                llm = LlmEvaluationResources.load(resources);
                var engine = Json.read(Files.readString(resources.resolve("config/engine.json")), EngineConfig.class);
                model = engine.models().get(modelPreset == null ? engine.evaluatorModelPreset() : modelPreset);
                if (model == null) throw new IllegalArgumentException("Unknown evaluator model preset");
                provenance.put("llmSources", new java.util.TreeMap<>(llm.sourceHashes()));
                provenance.put("evaluatorModel", model);
                protectedPaths.add(resources.resolve("config/engine.json"));
                for (String relative : llm.sourceHashes().keySet()) protectedPaths.add(resources.resolve(relative));
            }
            if (validate) {
                out.println("Selected evaluation settings and templates are valid. No credentials read or API calls made.");
                return 0;
            }
            for (var source : protectedPaths) if (sameFile(source, output))
                throw new IllegalArgumentException("Evaluation output must not overwrite its transcript or configuration");
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (!Files.isDirectory(parent) || !Files.isWritable(parent) || Files.isDirectory(output)
                    || Files.exists(output) && !Files.isWritable(output)) throw new IllegalArgumentException("Evaluation output is not writable");
            byte[] evidence = Files.readAllBytes(input);
            Transcript transcript = Json.read(new String(evidence, StandardCharsets.UTF_8), Transcript.class);
            if (assignments != null) assignments.validate(transcript);
            var targets = local == null ? List.<LocalEvaluationConfig.PolicyTarget>of() : local.policyTargets();
            for (var target : targets) if (transcript.events().stream().noneMatch(event -> event.topicIndex() == target.topicIndex()))
                throw new IllegalArgumentException("A policy target refers to a topic absent from this transcript");
            var client = local == null ? null : new HttpNlpClient(local.endpoint(), local.timeout());
            Map<String, Evaluator> evaluators = new LinkedHashMap<>();
            for (String method : selected) {
                if (method.equals(LLMEvaluator.ID)) {
                    ModelConfig chosen = model;
                    evaluators.put(method, new LLMEvaluator(request -> providers.apply(chosen).complete(request), chosen, llm));
                } else evaluators.put(method, new LocalNlpEvaluator(method, client, local));
            }
            var results = new EvaluationCoordinator(evaluators).evaluate(transcript);
            var report = new EvaluationReport(1, LlmEvaluationResources.sha256(evidence),
                    LlmEvaluationResources.sha256(Json.writeCanonical(provenance).getBytes(StandardCharsets.UTF_8)), targets, results);
            Object export = assignments == null ? report : OwnerEvaluationReport.create(report, assignments, assignmentsHash);
            Files.writeString(output, Json.write(export));
            results.forEach(result -> out.println(result.getEvaluatorId() + ": " + result.getStatus()));
            out.println((assignments == null ? "Separate evaluation results saved to " : "Private owner comparison saved to ") + output);
            return results.stream().anyMatch(result -> result.getStatus() == EvaluationStatus.FAILED) ? 1 : 0;
        } catch (IOException e) { throw new IllegalArgumentException("Could not read evaluation resources or write the evaluation report"); }
    }
    private static boolean sameFile(Path first, Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
    }
}
