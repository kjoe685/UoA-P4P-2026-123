package engine.evaluation.llm;

import engine.evaluation.EvaluationStatus;
import java.util.List;

/** Strict model output. Provenance and failure metadata are supplied by the engine, never the model. */
public record LlmAssessment(String topicId, List<ParticipantAssessment> participants) {
    public LlmAssessment { participants = List.copyOf(participants); }
    public record ParticipantAssessment(String participantId, List<MetricAssessment> metrics) {
        public ParticipantAssessment { metrics = List.copyOf(metrics); }
    }
    public record MetricAssessment(String metricId, EvaluationStatus status, Integer score,
                                   String explanation, List<String> evidenceTurnIds) {
        public MetricAssessment { evidenceTurnIds = List.copyOf(evidenceTurnIds); }
    }
}
