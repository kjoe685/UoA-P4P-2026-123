package engine.provider;

import engine.chat.ChatRequest;
import engine.config.ModelConfig;
import java.util.Set;

/** Conservative adapter capabilities. Unsupported options fail instead of being silently ignored. */
public final class ProviderCapabilities {
    private ProviderCapabilities() { }
    public static boolean structuredOutput(ModelConfig model) {
        return switch (model.provider()) {
            case "openai" -> model.model().matches("(gpt-4o.*|gpt-5.*|o[134].*)") && !model.model().startsWith("o1-mini");
            case "anthropic" -> model.model().matches("claude-(sonnet-4-[56].*|sonnet-5.*|haiku-4-5.*|opus-4-[5678].*|opus-5.*)");
            case "gemini", "grok", "ollama" -> true;
            default -> false;
        };
    }
    public static void validateOptions(String provider, String model, Double temperature, String effort) {
        if ("gemini".equals(provider) && !model.matches("[a-zA-Z0-9._-]+"))
            throw new IllegalArgumentException("Gemini model must be a model ID");
        if (effort != null) {
            boolean supported = switch (provider) {
                case "openai" -> model.startsWith("gpt-5") || ((model.startsWith("o3") || model.startsWith("o4")) && !effort.equals("minimal"));
                case "grok" -> model.startsWith("grok-3-mini") && Set.of("low", "high").contains(effort);
                default -> false;
            };
            if (!supported) throw new IllegalArgumentException("Reasoning effort is unsupported by this adapter/model");
        }
        if (temperature != null && "anthropic".equals(provider) && temperature > 1)
            throw new IllegalArgumentException("Anthropic temperature must be between 0 and 1");
        if (temperature != null && "openai".equals(provider)
                && (model.startsWith("gpt-5") || model.matches("o[134].*")))
            throw new IllegalArgumentException("Temperature is unsupported for this reasoning model preset");
    }
    static void check(ChatRequest request, String provider) {
        if (!provider.equals(request.model().provider())) throw new IllegalArgumentException("Provider mismatch");
        if (request.outputSchema() != null && !structuredOutput(request.model()))
            throw new IllegalArgumentException("Structured output is unsupported for this model");
    }
}
