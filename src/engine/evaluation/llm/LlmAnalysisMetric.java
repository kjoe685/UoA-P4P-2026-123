package engine.evaluation.llm;

import engine.chat.ChatResponse;
import engine.config.ModelConfig;
import engine.evaluation.EvaluationStatus;
import engine.evaluation.MetricValue;
import java.util.List;
import java.util.Map;

/** Separate topic judgments, with all attempts and no aggregate score. */
public record LlmAnalysisMetric(ModelConfig model, LlmRubric rubric, Map<String, String> sourceHashes,
                                 List<TopicResult> topics) implements MetricValue {
    public LlmAnalysisMetric { sourceHashes = Map.copyOf(sourceHashes); topics = List.copyOf(topics); }
    public record TopicResult(int topicIndex, EvaluationStatus status, String error,
                              LlmAssessment assessment, List<Attempt> attempts) {
        public TopicResult { attempts = List.copyOf(attempts); }
    }
    public record Attempt(String provider, String model, ChatResponse.CompletionStatus completionStatus,
                          ChatResponse.TokenUsage usage, long latencyMillis) { }
}
