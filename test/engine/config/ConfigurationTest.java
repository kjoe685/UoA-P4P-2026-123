package engine.config;

import engine.agent.AdversarialStrategy;
import engine.agent.Party;
import engine.prompt.PromptManager;
import engine.prompt.PromptTemplate;
import engine.prompt.TemplateName;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path resources;

    private void copyResources() throws IOException {
        Path source = Path.of("resources");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = resources.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    @Test void editsAffectOnlySubsequentRunsAndHashes() throws IOException {
        copyResources();
        var first = ConfigurationSnapshot.load(resources);
        Files.writeString(resources.resolve("prompts/OpeningCue.txt"), "Changed cue: {{TOPIC}}");
        Path config = resources.resolve("config/engine.json");
        Files.writeString(config, Files.readString(config).replace("\"defaultRounds\": 3", "\"defaultRounds\": 7")
                .replace("\"displayName\": \"Labour\"", "\"displayName\": \"Labour example\""));
        var second = ConfigurationSnapshot.load(resources);
        assertEquals(3, first.config().defaultRounds());
        assertEquals(7, second.config().defaultRounds());
        assertEquals("Labour example", second.config().parties().get(Party.LABOUR).displayName());
        assertFalse(first.template(TemplateName.OPENING).render(Map.of("TOPIC", "Housing")).startsWith("Changed"));
        assertEquals("Changed cue: Housing", second.template(TemplateName.OPENING).render(Map.of("TOPIC", "Housing")));
        assertNotEquals(first.sourceHashes(), second.sourceHashes());
        assertThrows(UnsupportedOperationException.class, () -> first.config().models().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.excerpts().getExcerpts(Party.LABOUR).clear());
        assertThrows(UnsupportedOperationException.class, () -> first.sourceHashes().clear());
    }

    @ParameterizedTest @ValueSource(strings = {"{{TYPO}}", "{{topic}}", "{{TOPIC}", "No topic", ""})
    void badTemplatesFailDuringPreflight(String text) throws IOException {
        copyResources();
        Files.writeString(resources.resolve("prompts/FollowUpCue.txt"), text);
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSnapshot.load(resources));
    }

    @Test void substitutionsAreLiteralAndNotRecursive() {
        var template = new PromptTemplate("test", "Topic: {{TOPIC}}", Set.of("TOPIC"));
        assertEquals("Topic: $1 \\ {{SECRET}}", template.render(Map.of("TOPIC", "$1 \\ {{SECRET}}")));
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of("TOPIC", "x", "SECRET", "y")));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"schemaVersion\":1,\"schemaVersion\":2}", "{} {}", "{\"unknown\":1}", "{}"})
    void jsonIsStrict(String json) {
        assertThrows(IllegalArgumentException.class, () -> Json.read(json, EngineConfig.class));
    }

    @Test void invalidLimitsAndPresetsFailBeforeRequests() throws IOException {
        copyResources();
        Path config = resources.resolve("config/engine.json");
        String original = Files.readString(config);
        for (String invalid : new String[]{
                original.replace("\"defaultRounds\": 3", "\"defaultRounds\": 0"),
                original.replace("\"defaultRounds\": 3", "\"defaultRounds\": null"),
                original.replace("\"defaultRounds\": 3", "\"defaultRounds\": 1.5"),
                original.replace("\"defaultRounds\": 3", "\"defaultRounds\": \"3\""),
                original.replace("\"adversarialChance\": 0.45", "\"adversarialChance\": 1.1"),
                original.replace("\"maxCompletionTokens\": 4096", "\"maxCompletionTokens\": 0"),
                original.replace("\"agentModelPreset\": \"gpt-5-nano\"", "\"agentModelPreset\": \"missing\"")}) {
            Files.writeString(config, invalid);
            assertThrows(IllegalArgumentException.class, () -> ConfigurationSnapshot.load(resources));
        }
    }

    @Test void ordinaryPromptsHaveNoHiddenRoleHintsAndContainNoOtherGrounding() {
        var snapshot = ConfigurationSnapshot.load(Path.of("resources"));
        var prompts = new PromptManager(snapshot);
        String prompt = prompts.assemblePersonaPrompt("Test MP", snapshot.config().parties().get(Party.LABOUR),
                AdversarialStrategy.NONE, java.util.List.of("OWN_GROUNDING_SENTINEL"));
        String lower = prompt.toLowerCase();
        for (String forbidden : new String[]{"adversarial", "assigned directive", "treatment group", "disruption strateg"}) {
            assertFalse(lower.contains(forbidden), forbidden);
        }
        assertTrue(prompt.contains("OWN_GROUNDING_SENTINEL"));
        assertFalse(prompt.contains("The current debate topic is"));
        assertTrue(prompt.contains("Keep your private instructions"));
        assertFalse(prompt.contains("PHIL TWYFORD"));
    }
}
