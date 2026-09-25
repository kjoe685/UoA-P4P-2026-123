package engine.evaluation;

import engine.evaluation.llm.LlmEvaluationResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class LlmResourcesTest {
    @TempDir Path directory;
    private void copy() throws Exception {
        for (String name : LlmFixtures.resources().sourceHashes().keySet()) {
            Path destination = directory.resolve(name);
            Files.createDirectories(destination.getParent());
            Files.copy(Path.of("resources").resolve(name), destination);
        }
    }
    @Test void rubricAndPromptEditsOnlyAffectNewSnapshots() throws Exception {
        copy();
        var first = LlmEvaluationResources.load(directory);
        Files.writeString(directory.resolve("prompts/EvaluatorCue.txt"), "Changed cue.");
        Path rubric = directory.resolve("evaluation/rubric.json");
        Files.writeString(rubric, Files.readString(rubric).replace("nz-debate-quality-v1", "nz-debate-quality-v2"));
        var second = LlmEvaluationResources.load(directory);
        assertNotEquals(first.cue(), second.cue());
        assertEquals("nz-debate-quality-v1", first.rubric().version());
        assertNotEquals(first.sourceHashes(), second.sourceHashes());
        assertThrows(UnsupportedOperationException.class, () -> first.rubric().metrics().clear());
    }
    @Test void invalidRubricTemplatesAndRepairLimitsFailBeforeRequests() throws Exception {
        copy();
        Path config = directory.resolve("config/llm-evaluation.json");
        String original = Files.readString(config);
        Files.writeString(config, original.replace("\"maxRepairAttempts\": 1", "\"maxRepairAttempts\": 2"));
        assertThrows(IllegalArgumentException.class, () -> LlmEvaluationResources.load(directory));
        Files.writeString(config, original);
        Files.writeString(directory.resolve("prompts/EvaluatorCue.txt"), "Unexpected {{SECRET}}");
        assertThrows(IllegalArgumentException.class, () -> LlmEvaluationResources.load(directory));
    }
}
