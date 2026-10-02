package engine.evaluation.llm;

import engine.prompt.PromptTemplate;
import engine.utils.Json;
import engine.utils.Utf8;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.CharacterCodingException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read once. Evaluation never loads private agent prompts, strategies or grounding. */
public record LlmEvaluationResources(LlmEvaluationConfig config, LlmRubric rubric,
                                     String systemPrompt, String cue, String repairPrompt,
                                     Map<String, String> sourceHashes) {
    public LlmEvaluationResources {
        sourceHashes = Map.copyOf(sourceHashes);
        cueTemplate(cue);
        repairTemplate(repairPrompt);
    }
    public String renderCue(List<LlmEvidenceEligibility.ParticipantAvailability> eligibility) {
        var values = cue.contains("{{EVIDENCE_ELIGIBILITY}}")
                ? Map.of("EVIDENCE_ELIGIBILITY", Json.write(eligibility)) : Map.<String,String>of();
        return cueTemplate(cue).render(values);
    }
    private static PromptTemplate cueTemplate(String text) {
        return new PromptTemplate("EvaluatorCue.txt", text,
                text != null && text.contains("{{EVIDENCE_ELIGIBILITY}}") ? Set.of("EVIDENCE_ELIGIBILITY") : Set.of());
    }
    public String renderRepair(List<LlmResponseValidator.ValidationCode> codes) {
        var values = repairPrompt.contains("{{VALIDATION_FAILURES}}")
                ? Map.of("VALIDATION_FAILURES", Json.write(codes.stream().map(Enum::name).distinct().toList())) : Map.<String,String>of();
        return repairTemplate(repairPrompt).render(values);
    }
    private static PromptTemplate repairTemplate(String text) {
        return new PromptTemplate("EvaluatorRepair.txt", text,
                text != null && text.contains("{{VALIDATION_FAILURES}}") ? Set.of("VALIDATION_FAILURES") : Set.of());
    }
    public static LlmEvaluationResources load(Path root) {
        return load(root,Map.of());
    }
    public static LlmEvaluationResources load(Path root,Map<String,String> replacements) {
        Map<String, String> hashes = new LinkedHashMap<>();
        var config = Json.read(read(root, "config/llm-evaluation.json", hashes,replacements), LlmEvaluationConfig.class);
        var rubric = Json.read(read(root, "evaluation/rubric.json", hashes,replacements), LlmRubric.class);
        String system = new PromptTemplate("EvaluatorPrompt.txt", read(root, "prompts/EvaluatorPrompt.txt", hashes,replacements), Set.of("RUBRIC"))
                .render(Map.of("RUBRIC", Json.writeCanonical(rubric)));
        String cue = read(root, "prompts/EvaluatorCue.txt", hashes,replacements);
        String repair = read(root, "prompts/EvaluatorRepair.txt", hashes,replacements);
        return new LlmEvaluationResources(config, rubric, system, cue, repair, hashes);
    }
    private static String read(Path root, String relative, Map<String, String> hashes,Map<String,String> replacements) {
        try {
            byte[] bytes = replacements.containsKey(relative) ? Utf8.encode(replacements.get(relative)) : Files.readAllBytes(root.resolve(relative));
            String text=Utf8.decode(bytes);
            hashes.put(relative, sha256(bytes));
            return text;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("LLM evaluation resource must contain valid UTF-8/Unicode text: " + relative); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot read LLM evaluation resource: " + relative); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
