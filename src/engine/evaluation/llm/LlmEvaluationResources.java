package engine.evaluation.llm;

import engine.prompt.PromptTemplate;
import engine.utils.Json;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read once. Evaluation never loads private agent prompts, strategies or grounding. */
public record LlmEvaluationResources(LlmEvaluationConfig config, LlmRubric rubric,
                                     String systemPrompt, String cue, String repairPrompt,
                                     Map<String, String> sourceHashes) {
    public LlmEvaluationResources { sourceHashes = Map.copyOf(sourceHashes); }
    public static LlmEvaluationResources load(Path root) {
        Map<String, String> hashes = new LinkedHashMap<>();
        var config = Json.read(read(root, "config/llm-evaluation.json", hashes), LlmEvaluationConfig.class);
        var rubric = Json.read(read(root, "evaluation/rubric.json", hashes), LlmRubric.class);
        String system = new PromptTemplate("EvaluatorPrompt.txt", read(root, "prompts/EvaluatorPrompt.txt", hashes), Set.of("RUBRIC"))
                .render(Map.of("RUBRIC", Json.writeCanonical(rubric)));
        String cue = new PromptTemplate("EvaluatorCue.txt", read(root, "prompts/EvaluatorCue.txt", hashes), Set.of()).render(Map.of());
        String repair = new PromptTemplate("EvaluatorRepair.txt", read(root, "prompts/EvaluatorRepair.txt", hashes), Set.of()).render(Map.of());
        return new LlmEvaluationResources(config, rubric, system, cue, repair, hashes);
    }
    private static String read(Path root, String relative, Map<String, String> hashes) {
        try {
            byte[] bytes = Files.readAllBytes(root.resolve(relative));
            hashes.put(relative, sha256(bytes));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read LLM evaluation resource: " + relative); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
