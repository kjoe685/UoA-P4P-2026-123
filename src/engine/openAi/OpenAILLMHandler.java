package engine.openAi;

import engine.LLMHandler;

import java.net.http.HttpResponse;

public class OpenAILLMHandler implements LLMHandler {

    private String apiKey;
    private String model;

    public OpenAILLMHandler(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public void setApiKey(String apiKey) {}

    public void setModel(String model) {}

    public HttpResponse<String> sendRequest() { return null; }
}