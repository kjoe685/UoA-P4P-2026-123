package engine.evaluation.llm;

public record LlmEvaluationConfig(int schemaVersion, int maxRepairAttempts, int maxCalls,
                                  int maxInputCharacters, int maxResponseCharacters,
                                  int maxTotalCompletionTokens) {
    public LlmEvaluationConfig {
        if (schemaVersion != 1 || maxRepairAttempts < 0 || maxRepairAttempts > 1 || maxCalls < 1 || maxCalls > 1000
                || maxInputCharacters < 1000 || maxInputCharacters > 2_000_000
                || maxResponseCharacters < 1000 || maxResponseCharacters > 1_000_000
                || maxTotalCompletionTokens < 1 || maxTotalCompletionTokens > 10_000_000)
            throw new IllegalArgumentException("Invalid LLM evaluation budget");
    }
}
