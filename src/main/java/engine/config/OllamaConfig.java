package engine.config;

import engine.utils.Json;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import java.util.Locale;
import java.util.function.Function;

/** Non-secret local runtime settings. Capture once per operation, never in public evidence. */
public record OllamaConfig(int schemaVersion,URI baseUrl,int contextTokens,int startupTimeoutSeconds,int downloadTimeoutSeconds) {
    public OllamaConfig {
        if (schemaVersion!=1 || baseUrl==null || !"http".equals(baseUrl.getScheme()) || baseUrl.getHost()==null
                || !Set.of("localhost","127.0.0.1","[::1]").contains(baseUrl.getHost())
                || baseUrl.getRawUserInfo()!=null || baseUrl.getRawQuery()!=null || baseUrl.getRawFragment()!=null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/")) || baseUrl.getPort()==0
                || baseUrl.getPort()>65535 || contextTokens<1024 || contextTokens>131072
                || startupTimeoutSeconds<1 || startupTimeoutSeconds>120 || downloadTimeoutSeconds<1 || downloadTimeoutSeconds>14400)
            throw new IllegalArgumentException("Invalid loopback Ollama runtime settings");
    }
    public static OllamaConfig defaults() { return new OllamaConfig(1,URI.create("http://127.0.0.1:11434"),16384,30,7200); }
    public static OllamaConfig load(Path root) {
        try { return Json.read(Files.readString(root.resolve("config/ollama.json")),OllamaConfig.class).effective(System::getenv); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read local LLM settings"); }
    }
    public OllamaConfig effective(Function<String,String> environment) {
        String base=environment.apply("OLLAMA_BASE_URL"), context=environment.apply("OLLAMA_CONTEXT_TOKENS");
        try {
            return new OllamaConfig(schemaVersion,base==null || base.isBlank() ? baseUrl : URI.create(base),
                    context==null || context.isBlank() ? contextTokens : Integer.parseInt(context),startupTimeoutSeconds,downloadTimeoutSeconds);
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid Ollama environment settings"); }
    }
    public static void requireLocalModel(String model) {
        localModelId(model);
    }
    /** Source intent is a suffix, not part of the cached registry model/tag identity. */
    public static String localModelId(String model) {
        if (model==null || model.length()>200) throw new IllegalArgumentException("Choose a local Ollama model ID");
        String base=model.toLowerCase(Locale.ROOT).endsWith(":local") ? model.substring(0,model.length()-6) : model;
        String lower=base.toLowerCase(Locale.ROOT);
        if (!base.matches("[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9._-]+)?")
                || base.contains("..") || lower.endsWith(":cloud") || lower.endsWith("-cloud") || lower.endsWith(":local"))
            throw new IllegalArgumentException("Choose a local Ollama model ID");
        return base;
    }
    public static String localModelReference(String model) {
        String base=localModelId(model);
        return (base.contains(":") ? base : base+":latest")+":local";
    }
}
