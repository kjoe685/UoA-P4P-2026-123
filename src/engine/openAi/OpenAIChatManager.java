package engine.openAi;

import engine.provider.ChatCompletionsProvider;
import engine.provider.ProviderHttp;
import java.net.URI;
import java.net.http.HttpClient;

public final class OpenAIChatManager extends ChatCompletionsProvider {
    public OpenAIChatManager(String apiKey) {
        this(apiKey, ProviderHttp.client(), URI.create("https://api.openai.com/v1/chat/completions"));
    }
    // Package-private seam for local tests; credentials cannot be redirected through run configuration.
    OpenAIChatManager(String key, HttpClient client, URI endpoint) {
        super("openai", "OpenAI", key, client, endpoint);
    }
}
