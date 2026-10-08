package engine.provider;

import engine.chat.ChatRequest;
import engine.config.ModelConfig;
import java.util.Set;

/** Conservative adapter capabilities. Unsupported options fail instead of being silently ignored. */
public final class ProviderCapabilities {
    private static final Set<String> LEGACY_OPENAI_EFFORTS = Set.of("minimal", "low", "medium", "high");
    private static final Set<String> LUNA_EFFORTS = Set.of("none", "low", "medium", "high", "xhigh", "max");
    private ProviderCapabilities() { }
    public static boolean structuredOutput(ModelConfig model) {
        return switch (model.provider()) {
            case "openai" -> model.model().equals("gpt-6-luna")
                    || model.model().matches("(gpt-4o.*|gpt-5.*|o[134].*)") && !model.model().startsWith("o1-mini");
            case "anthropic" -> model.model().matches("claude-(sonnet-4-[56].*|sonnet-5.*|haiku-4-5.*|opus-4-[5678].*|opus-5.*)");
            case "gemini", "grok", "ollama" -> true;
            default -> false;
        };
    }
    public static void validateOptions(String provider, String model, Double temperature, String effort) {
        boolean luna = "openai".equals(provider) && model.equals("gpt-6-luna");
        if (temperature != null && effort != null && !(luna && effort.equals("none")))
            throw new IllegalArgumentException("Configure temperature or reasoning effort, not both");
        if ("ollama".equals(provider)) engine.config.OllamaConfig.requireLocalModel(model);
        if ("gemini".equals(provider) && !model.matches("[a-zA-Z0-9._-]+"))
            throw new IllegalArgumentException("Gemini model must be a model ID");
        if (effort != null) {
            boolean supported = switch (provider) {
                case "openai" -> luna ? LUNA_EFFORTS.contains(effort)
                        : LEGACY_OPENAI_EFFORTS.contains(effort) && (model.startsWith("gpt-5")
                            || ((model.startsWith("o3") || model.startsWith("o4")) && !effort.equals("minimal")));
                case "grok" -> model.startsWith("grok-3-mini") && Set.of("low", "high").contains(effort);
                default -> false;
            };
            if (!supported) throw new IllegalArgumentException("Reasoning effort is unsupported by this adapter/model");
        }
        if (temperature != null && "anthropic".equals(provider) && temperature > 1)
            throw new IllegalArgumentException("Anthropic temperature must be between 0 and 1");
        // Omitted Luna effort defaults to medium, so sampling needs an explicit none.
        if (temperature != null && luna && !"none".equals(effort))
            throw new IllegalArgumentException("GPT-6 Luna temperature requires reasoning effort none");
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
