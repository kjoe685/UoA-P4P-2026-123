package engine.evaluation;

import engine.ChatManager;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.config.ModelConfig;
import engine.transcript.Transcript;
import engine.prompt.PromptTemplate;
import engine.utils.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Existing provisional metric parser, adapted to isolated requests. Full rubrics/validation are stage three. */
public final class LLMEvaluator implements Evaluator {
    private final String evaluatorId;
    private final ChatManager chatManager;
    private final ModelConfig model;
    private final String systemPrompt;
    private final String cue;

    public LLMEvaluator(String evaluatorId, ChatManager chatManager, ModelConfig model,
                        PromptTemplate instructions, PromptTemplate cue) {
        this.evaluatorId = Objects.requireNonNull(evaluatorId);
        this.chatManager = Objects.requireNonNull(chatManager);
        this.model = Objects.requireNonNull(model);
        this.systemPrompt = instructions.render(Map.of());
        this.cue = cue.render(Map.of());
    }

    @Override
    public EvaluationResult evaluate(Transcript transcript) {
        var request = new ChatRequest(systemPrompt, List.of(
                new ChatMessage(ChatMessage.Role.USER, Json.write(transcript)),
                new ChatMessage(ChatMessage.Role.USER, cue)), model);
        return parseResult(chatManager.complete(request).requireCompletedText());
    }

    @SuppressWarnings("unchecked")
    private EvaluationResult parseResult(String response) {
        Objects.requireNonNull(response, "The LLM returned no evaluation response");

        Object parsed;
        try {
            parsed = Json.parse(stripCodeFence(response));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The LLM evaluation response is not valid JSON", exception);
        }

        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("The LLM evaluation response must be a JSON object");
        }

        Map<String, Object> root = (Map<String, Object>) parsed;
        Object metricsObject = root.containsKey("metrics") ? root.get("metrics") : root;
        if (!(metricsObject instanceof Map)) {
            throw new IllegalArgumentException("The 'metrics' value must be a JSON object");
        }

        Map<String, MetricValue> metrics = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) metricsObject).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("Metric names must be strings");
            }
            String name = (String) entry.getKey();
            metrics.put(name, toMetricValue(name, entry.getValue()));
        }

        return new EvaluationResult(evaluatorId, metrics);
    }

    private MetricValue toMetricValue(String name, Object rawValue) {
        if (rawValue instanceof Number) {
            return new NumericMetric(((Number) rawValue).doubleValue(), null);
        }
        if (rawValue instanceof String) {
            return new TextMetric((String) rawValue);
        }
        if (rawValue instanceof Map) {
            return toStructuredMetricValue(name, (Map<?, ?>) rawValue);
        }
        throw unsupportedMetricValue(name);
    }

    private MetricValue toStructuredMetricValue(String name, Map<?, ?> metricObject) {
        Object value = metricObject.get("value");
        if (value instanceof Number) {
            Object explanation = metricObject.get("explanation");
            if (explanation != null && !(explanation instanceof String)) {
                throw new IllegalArgumentException(
                        "The explanation for metric '" + name + "' must be a string");
            }
            return new NumericMetric(
                    ((Number) value).doubleValue(),
                    (String) explanation);
        }
        if (value instanceof String) {
            return new TextMetric((String) value);
        }
        throw unsupportedMetricValue(name);
    }

    private IllegalArgumentException unsupportedMetricValue(String name) {
        return new IllegalArgumentException(
                "Metric '" + name + "' must be a string, number, or object containing a string or number value");
    }

    private String stripCodeFence(String response) {
        String trimmed = response.trim();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return trimmed;
        }

        int firstLineBreak = trimmed.indexOf('\n');
        if (firstLineBreak < 0) {
            return trimmed;
        }
        return trimmed.substring(firstLineBreak + 1, trimmed.length() - 3).trim();
    }
}
