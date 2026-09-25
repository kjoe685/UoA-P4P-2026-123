package engine.evaluation;

import engine.agent.AdversarialStrategy;
import engine.evaluation.llm.LlmAnalysisMetric;
import engine.evaluation.llm.LlmAssessment;
import engine.transcript.Transcript;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Explicit owner-only export. Hidden assignments are joined AFTER all model calls. */
public record OwnerEvaluationReport(int schemaVersion, EvaluationReport evaluation, String assignmentsSha256,
                                     List<Comparison> assignmentComparison) {
    public OwnerEvaluationReport { assignmentComparison = List.copyOf(assignmentComparison); }
    public record Assignments(int schemaVersion, Map<String, AdversarialStrategy> assignments) {
        public Assignments {
            assignments = Map.copyOf(assignments);
            if (schemaVersion != 1 || assignments.isEmpty() || assignments.keySet().stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("Invalid owner assignments");
        }
        public void validate(Transcript transcript) {
            var ids = transcript.events().stream().filter(e -> e.speaker() != null).map(e -> e.speaker().id()).collect(Collectors.toSet());
            if (!ids.containsAll(assignments.keySet())) throw new IllegalArgumentException("Owner assignment references an unobserved participant");
        }
        @Override public String toString() { return "Assignments[private assignments redacted]"; }
    }
    /** Side-by-side evidence for human comparison; no inferred intent or automatic correctness label. */
    public record Comparison(int topicIndex, String participantId, AdversarialStrategy assignedStrategy,
                              EvaluationStatus topicStatus, String topicError,
                              LlmAssessment.MetricAssessment observedRhetoric) { }

    public static OwnerEvaluationReport create(EvaluationReport report, Assignments assignments, String hash) {
        List<Comparison> comparisons = new ArrayList<>();
        for (var method : report.methods()) {
            if (!LLMEvaluator.ID.equals(method.getEvaluatorId())) continue;
            var value = method.getMetrics().get("assessments");
            if (!(value instanceof LlmAnalysisMetric analysis)) continue;
            for (var topic : analysis.topics()) {
                for (var entry : new java.util.TreeMap<>(assignments.assignments()).entrySet()) {
                    LlmAssessment.MetricAssessment observed = null;
                    if (topic.assessment() != null) observed = topic.assessment().participants().stream()
                            .filter(p -> p.participantId().equals(entry.getKey())).flatMap(p -> p.metrics().stream())
                            .filter(m -> m.metricId().equals("rhetorical_tactics")).findFirst().orElse(null);
                    comparisons.add(new Comparison(topic.topicIndex(), entry.getKey(), entry.getValue(), topic.status(), topic.error(), observed));
                }
            }
        }
        return new OwnerEvaluationReport(1, report, hash, comparisons);
    }
}
