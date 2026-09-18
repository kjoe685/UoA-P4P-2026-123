package engine.evaluation;

import engine.transcript.Transcript;

/** Independent evaluation method over a frozen public transcript; no feedback into agents. */
@FunctionalInterface
public interface Evaluator {
    EvaluationResult evaluate(Transcript transcript);
}
