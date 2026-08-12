package engine.evaluation;

import engine.ChatManager;
import engine.utils.FileTextReader;
import engine.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LLMEvaluator implements Evaluator {
    private static final String EVALUATOR_PROMPT_PATH = "resources/prompts/EvaluatorPrompt.txt";
    private static final String DEFAULT_EVALUATOR_ID = "llm";
    private static final String EVALUATION_CUE =
            "Evaluate the debate responses above and return the requested metrics as JSON.";

    private final String evaluatorId;
    private final ChatManager chatManager;

    public LLMEvaluator(ChatManager chatManager) {
        this(DEFAULT_EVALUATOR_ID, chatManager, new FileTextReader());
    }

    public LLMEvaluator(String evaluatorId, ChatManager chatManager) {
        this(evaluatorId, chatManager, new FileTextReader());
    }

    public LLMEvaluator(ChatManager chatManager, FileTextReader fileTextReader) {
        this(DEFAULT_EVALUATOR_ID, chatManager, fileTextReader);
    }

    public LLMEvaluator(String evaluatorId, ChatManager chatManager, FileTextReader fileTextReader) {
        this.evaluatorId = Objects.requireNonNull(evaluatorId, "evaluatorId");
        this.chatManager = Objects.requireNonNull(chatManager, "chatManager");
        Objects.requireNonNull(fileTextReader, "fileTextReader");

        chatManager.addMessage(fileTextReader.readText(EVALUATOR_PROMPT_PATH));
    }

    @Override
    public void hear(String speaker, String message) {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(message, "message");
        chatManager.addMessage(speaker + ": " + message);
    }

    @Override
    public EvaluationResult evaluate() {
        chatManager.addMessage(EVALUATION_CUE);
        chatManager.sendChat();
        return parseResult(chatManager.getMessageContent());
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
