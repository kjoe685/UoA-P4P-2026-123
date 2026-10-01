package engine.provider;

import java.net.URI;
import java.net.http.HttpClient;

public final class GrokChatManager extends ChatCompletionsProvider {
    public GrokChatManager(String key) {
        this(key, ProviderHttp.client(), URI.create("https://api.x.ai/v1/chat/completions"));
    }
    GrokChatManager(String key, HttpClient client, URI endpoint) { super("grok", "Grok", key, client, endpoint); }
}
