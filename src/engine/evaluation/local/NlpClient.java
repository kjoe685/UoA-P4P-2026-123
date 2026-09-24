package engine.evaluation.local;

@FunctionalInterface
public interface NlpClient {
    NlpResponse analyze(NlpRequest request);
}
