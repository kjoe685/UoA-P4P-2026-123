package engine.evaluation.local;

import engine.evaluation.MetricValue;
import java.util.List;

/** Keep batches, provenance, scales, and item failures intact. There is no aggregate score. */
public record LocalAnalysisMetric(List<NlpResponse.Method> batches) implements MetricValue {
    public LocalAnalysisMetric { batches = List.copyOf(batches); }
}
