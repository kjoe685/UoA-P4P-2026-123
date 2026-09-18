package engine.chat;

import java.util.Objects;

public record ChatResponse(String text, String provider, String model, CompletionStatus status,
                           TokenUsage usage, long latencyMillis) {
    public enum CompletionStatus { COMPLETED, TRUNCATED, REFUSED, UNSUPPORTED }

    /** Null usage on the response means the provider did not report it. */
    public record TokenUsage(long inputTokens, long outputTokens) {
        public TokenUsage {
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("Negative token usage");
        }
    }

    public ChatResponse {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(status, "status");
        if (latencyMillis < 0) throw new IllegalArgumentException("Negative latency");
    }

    public String requireCompletedText() {
        if (status != CompletionStatus.COMPLETED) {
            throw new IllegalStateException("Model response was " + status.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (text == null || text.isBlank()) throw new IllegalStateException("Model returned no spoken text");
        return text;
    }

    @Override public String toString() { return "ChatResponse[" + status + ", content redacted]"; }
}
