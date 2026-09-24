package engine.evaluation;

import engine.evaluation.local.LocalEvaluationConfig;
import java.util.List;

/** The hashes bind a report to its original public evidence and evaluation settings. */
public record EvaluationReport(int schemaVersion, String transcriptSha256, String configurationSha256,
                               List<LocalEvaluationConfig.PolicyTarget> policyTargets, List<EvaluationResult> methods) {
    public EvaluationReport { policyTargets = List.copyOf(policyTargets); methods = List.copyOf(methods); }
}
