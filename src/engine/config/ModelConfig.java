package engine.config;

import java.util.Set;

/** Non-secret generation settings. Credentials belong only to the provider adapter. */
public record ModelConfig(String provider, String model, Double temperature, String reasoningEffort,
                          int maxCompletionTokens, int timeoutSeconds) {
    public ModelConfig {
        if (!"openai".equals(provider)) {
            throw new IllegalArgumentException("Only the openai provider is implemented in this stage");
        }
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Model name is required");
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Temperature must be between 0 and 2");
        }
        if (reasoningEffort != null && !Set.of("minimal", "low", "medium", "high").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Unsupported reasoning effort");
        }
        if (temperature != null && reasoningEffort != null) {
            throw new IllegalArgumentException("Configure temperature or reasoning effort, not both");
        }
        if (maxCompletionTokens < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Token limit and timeout must be positive");
        }
    }
}
