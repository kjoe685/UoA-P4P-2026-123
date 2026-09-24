package engine.evaluation;

import engine.transcript.Transcript;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs independent methods; never combines scores or writes results into the transcript. */
public final class EvaluationCoordinator {
    private final Map<String, Evaluator> evaluators;

    public EvaluationCoordinator(Map<String, Evaluator> evaluators) {
        this.evaluators = Collections.unmodifiableMap(new LinkedHashMap<>(evaluators));
        this.evaluators.forEach((id, evaluator) -> {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Evaluator ID is required");
            Objects.requireNonNull(evaluator);
        });
    }

    public List<EvaluationResult> evaluate(Transcript transcript) {
        Objects.requireNonNull(transcript);
        List<EvaluationResult> results = new ArrayList<>();
        evaluators.forEach((id, evaluator) -> {
            try {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException();
                EvaluationResult result = Objects.requireNonNull(evaluator.evaluate(transcript));
                if (!id.equals(result.getEvaluatorId())) throw new IllegalStateException();
                results.add(result);
            } catch (RuntimeException e) {
                results.add(new EvaluationResult(id, Map.of(), EvaluationStatus.FAILED,
                        Thread.currentThread().isInterrupted() ? "evaluation_interrupted" : "evaluation_failed"));
            }
        });
        return List.copyOf(results);
    }
}
