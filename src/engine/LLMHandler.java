package engine;

import java.net.http.HttpResponse;

public interface LLMHandler {
    void setApiKey(String apiKey);
    void setModel(String model);
    HttpResponse<String> sendRequest();
}
