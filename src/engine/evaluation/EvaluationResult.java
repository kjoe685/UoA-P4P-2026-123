package engine.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EvaluationResult {
    private final String evaluatorId;
    private final Map<String, MetricValue> metrics;

    public EvaluationResult(String evaluatorId, Map<String, MetricValue> metrics) {
        this.evaluatorId = Objects.requireNonNull(evaluatorId, "evaluatorId");
        Objects.requireNonNull(metrics, "metrics");
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    public String getEvaluatorId() {
        return evaluatorId;
    }

    public Map<String, MetricValue> getMetrics() {
        return metrics;
    }
}
