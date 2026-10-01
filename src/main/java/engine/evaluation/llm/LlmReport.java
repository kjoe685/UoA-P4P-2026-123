package engine.evaluation.llm;

/** Derived public evidence assessment. Contains no private assignments or aggregate scores. */
public record LlmReport(int schemaVersion,String runId,String transcriptSha256,String configurationSha256,
                        long createdAt,LlmAnalysisMetric assessments) { }
