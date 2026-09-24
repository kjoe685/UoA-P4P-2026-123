package engine.evaluation.local;

import engine.evaluation.EvaluationResult;
import engine.evaluation.EvaluationStatus;
import engine.evaluation.Evaluator;
import engine.transcript.Transcript;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One independently selected method, with no dependency on agent configuration or providers. */
public final class LocalNlpEvaluator implements Evaluator {
    private final String method;
    private final NlpClient client;
    private final LocalEvaluationConfig config;

    public LocalNlpEvaluator(String method, NlpClient client, LocalEvaluationConfig config) {
        if (!LocalEvaluationConfig.METHODS.contains(method)) throw new IllegalArgumentException("Unknown NLP method");
        this.method = method;
        this.client = Objects.requireNonNull(client);
        this.config = Objects.requireNonNull(config);
    }

    @Override public EvaluationResult evaluate(Transcript transcript) {
        List<NlpResponse.Method> results = new ArrayList<>();
        for (NlpRequest request : NlpInputMapper.batches(transcript, method, config)) {
            try {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException();
                NlpResponse response = client.analyze(request);
                NlpResponseValidator.validate(request, response);
                results.add(response.methods().get(0));
            } catch (RuntimeException e) {
                // Retain successful earlier batches while surfacing transport/schema failure for this batch.
                results.add(new NlpResponse.Method(method, "failed", "transport_or_response_failed", null,
                        request.turns().stream().flatMap(turn -> {
                            List<String> ids = method.equals("deberta-stance") && !turn.targets().isEmpty()
                                    ? turn.targets().stream().map(NlpRequest.Target::id).toList()
                                    : java.util.Collections.singletonList(null);
                            return ids.stream().map(id -> new NlpResponse.Item(turn.turnId(), id, "failed",
                                    "transport_or_response_failed", List.of(), 0));
                        }).toList(), 0));
            }
        }
        EvaluationStatus status = results.stream().anyMatch(result -> result.status().equals("failed")) ? EvaluationStatus.FAILED
                : results.stream().allMatch(result -> result.status().equals("insufficient_evidence")) ? EvaluationStatus.INSUFFICIENT_EVIDENCE
                : EvaluationStatus.OK;
        return new EvaluationResult(method, Map.of("analysis", new LocalAnalysisMetric(results)), status,
                status == EvaluationStatus.FAILED ? "method_or_batch_failed"
                        : status == EvaluationStatus.INSUFFICIENT_EVIDENCE ? "no_evaluable_speeches_or_targets" : null);
    }
}
