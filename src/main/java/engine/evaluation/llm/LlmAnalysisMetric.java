package engine.evaluation.llm;

import engine.chat.ChatResponse;
import engine.config.ModelConfig;
import engine.evaluation.EvaluationStatus;

import java.util.List;
import java.util.Map;

/** Separate topic judgments, with all attempts and no aggregate score. */
public record LlmAnalysisMetric(String implementationVersion,ModelConfig model, LlmRubric rubric, Map<String, String> sourceHashes,
                                 List<TopicResult> topics)  {
    public LlmAnalysisMetric { sourceHashes = Map.copyOf(sourceHashes); topics = List.copyOf(topics); }
    public record TopicResult(String topicId, EvaluationStatus status, String error,
                              LlmAssessment assessment, List<Attempt> attempts) {
        public TopicResult { attempts = List.copyOf(attempts); }
    }
    public record Attempt(String provider, String model, ChatResponse.CompletionStatus completionStatus,
                          ChatResponse.TokenUsage usage, long latencyMillis,String requestSha256) { }
}
