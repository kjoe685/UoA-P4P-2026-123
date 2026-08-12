package engine.evaluation;

public interface Evaluator {
    EvaluationResult evaluate();
    void hear(String speaker, String message);
}
