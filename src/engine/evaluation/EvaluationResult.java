package engine.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EvaluationResult {
    private final String evaluatorId;
    private final Map<String, MetricValue> metrics;
    private final EvaluationStatus status;
    private final String error;

    public EvaluationResult(String evaluatorId, Map<String, MetricValue> metrics) {
        this(evaluatorId, metrics, EvaluationStatus.OK, null);
    }

    public EvaluationResult(String evaluatorId, Map<String, MetricValue> metrics,
                            EvaluationStatus status, String error) {
        this.evaluatorId = Objects.requireNonNull(evaluatorId, "evaluatorId");
        Objects.requireNonNull(metrics, "metrics");
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        this.status = Objects.requireNonNull(status, "status");
        this.error = error;
    }

    public EvaluationStatus getStatus() { return status; }
    public String getError() { return error; }

    public String getEvaluatorId() {
        return evaluatorId;
    }

    public Map<String, MetricValue> getMetrics() {
        return metrics;
    }
}
