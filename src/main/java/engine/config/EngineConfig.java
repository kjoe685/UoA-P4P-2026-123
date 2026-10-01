package engine.config;

import engine.agent.Party;
import java.util.Map;
import java.util.Objects;

/** Owner-side configuration. Never place this object in a model request or public event. */
public record EngineConfig(int schemaVersion, int defaultRounds, String defaultTopic,
                           String agentModelPreset, String evaluatorModelPreset,
                           Map<String, ModelConfig> models, Map<Party, PartyProfile> parties,
                           InterruptionConfig interruptions) {
    public EngineConfig {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported configuration schemaVersion");
        if (defaultRounds < 1 || defaultRounds > 10) throw new IllegalArgumentException("defaultRounds must be between 1 and 10");
        if (defaultTopic == null || defaultTopic.isBlank() || defaultTopic.length() > 300) throw new IllegalArgumentException("defaultTopic needs 1–300 characters");
        models = Map.copyOf(models);
        parties = Map.copyOf(parties);
        Objects.requireNonNull(interruptions, "interruptions");
        if (agentModelPreset == null || evaluatorModelPreset == null
                || !models.containsKey(agentModelPreset) || !models.containsKey(evaluatorModelPreset)) {
            throw new IllegalArgumentException("Agent and evaluator model presets must exist in models");
        }
        for (Party party : Party.values()) {
            if (!parties.containsKey(party)) throw new IllegalArgumentException("Missing party profile: " + party);
        }
    }

    public ModelConfig agentModel() { return models.get(agentModelPreset); }
    public ModelConfig evaluatorModel() { return models.get(evaluatorModelPreset); }

    @Override public String toString() { return "EngineConfig[private run configuration]"; }
}
