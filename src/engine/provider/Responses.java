package engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import engine.chat.ChatResponse;
import engine.chat.ChatResponse.CompletionStatus;

final class Responses {
    private Responses() { }
    static ChatResponse create(JsonNode root, String text, String provider, String requestedModel,
                               CompletionStatus status, JsonNode input, JsonNode output, long start) {
        if (status == CompletionStatus.COMPLETED && (text == null || text.isBlank())) throw new IllegalArgumentException();
        ChatResponse.TokenUsage usage = null;
        if (input.isIntegralNumber() && output.isIntegralNumber() && input.canConvertToLong() && output.canConvertToLong())
            usage = new ChatResponse.TokenUsage(input.longValue(), output.longValue());
        String model = root.path("model").isTextual() ? root.path("model").textValue() : requestedModel;
        return new ChatResponse(text, provider, model, status, usage, (System.nanoTime() - start) / 1_000_000);
    }
}
