package engine.provider;

import engine.ChatManager;
import engine.config.ModelConfig;
import engine.openAi.OpenAIChatManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Lazy credential lookup: selecting a local method never reads a cloud key. */
public final class ProviderFactory {
    private final Function<String, String> environment;
    private final Path legacyOpenAiKey;
    private final Map<String, ChatManager> providers = new HashMap<>();
    public ProviderFactory() { this(System::getenv, Path.of("keys/openAi/OpenAI_Key.txt")); }
    ProviderFactory(Function<String, String> environment, Path legacyOpenAiKey) {
        this.environment = environment; this.legacyOpenAiKey = legacyOpenAiKey;
    }
    public ChatManager forModel(ModelConfig model) { return providers.computeIfAbsent(model.provider(), this::create); }
    private ChatManager create(String provider) {
        return switch (provider) {
            case "openai" -> new OpenAIChatManager(key("OPENAI_API_KEY", true));
            case "anthropic" -> new AnthropicChatManager(key("ANTHROPIC_API_KEY", false));
            case "gemini" -> new GeminiChatManager(key("GEMINI_API_KEY", false));
            case "grok" -> new GrokChatManager(key("XAI_API_KEY", false));
            case "ollama" -> new OllamaChatManager(ollamaBase(), ollamaContext());
            default -> throw new IllegalArgumentException("Unsupported provider");
        };
    }
    private URI ollamaBase() {
        String value = environment.apply("OLLAMA_BASE_URL");
        try { return URI.create(value == null || value.isBlank() ? "http://127.0.0.1:11434" : value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid Ollama base URL"); }
    }
    private int ollamaContext() {
        String value = environment.apply("OLLAMA_CONTEXT_TOKENS");
        try { return value == null || value.isBlank() ? 16384 : Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Ollama context limit"); }
    }
    private String key(String name, boolean allowLegacy) {
        String value = environment.apply(name);
        if ((value == null || value.isBlank()) && allowLegacy) {
            try { value = Files.readString(legacyOpenAiKey).trim(); }
            catch (IOException e) { value = null; }
        }
        if (value == null || value.isBlank() || value.contains("#")) throw new IllegalArgumentException("Set " + name + " before selecting this provider");
        return value;
    }
}
