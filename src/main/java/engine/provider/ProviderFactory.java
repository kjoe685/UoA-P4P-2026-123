package engine.provider;

import engine.ChatManager;
import engine.config.ModelConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Lazy credential lookup: selecting a local method never reads a cloud key. */
public final class ProviderFactory {
    private final Function<String, String> environment;
    private final Path legacyOpenAiKey;
    private final Path openAiKey;
    private final Map<String, ChatManager> providers = new ConcurrentHashMap<>();
    public ProviderFactory() { this(Path.of(".")); }
    public ProviderFactory(Path root) {
        this(System::getenv, root.resolve("keys/openAi/OpenAI_Key.txt"), root.resolve("keys/OpenAI_Key.txt"));
    }
    ProviderFactory(Function<String, String> environment, Path legacyOpenAiKey) {
        this(environment, null, legacyOpenAiKey);
    }
    ProviderFactory(Function<String, String> environment, Path openAiKey, Path legacyOpenAiKey) {
        this.environment = environment; this.openAiKey = openAiKey; this.legacyOpenAiKey = legacyOpenAiKey;
    }
    public ChatManager forModel(ModelConfig model) { return providers.computeIfAbsent(model.provider(), this::create); }
    private ChatManager create(String provider) {
        return switch (provider) {
            case "demo" -> new engine.demo.DemoChatManager();
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
            String current = fileKey(openAiKey), legacy = fileKey(legacyOpenAiKey);
            if (current != null && legacy != null && !current.equals(legacy))
                throw new IllegalArgumentException("OpenAI key files disagree. Set OPENAI_API_KEY or keep one key file.");
            value = current != null ? current : legacy;
        }
        if (value == null || value.isBlank() || value.contains("#")) throw new IllegalArgumentException("Set " + name + " before selecting this provider");
        return ProviderHttp.credential(value.trim());
    }
    private static String fileKey(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try { return Files.readString(path).trim(); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot read the OpenAI key file"); }
    }
}
