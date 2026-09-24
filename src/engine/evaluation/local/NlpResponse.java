package engine.evaluation.local;

import java.util.List;
import java.util.Map;

/** Versioned service response. Validation against the submitted evidence is separate from transport. */
public record NlpResponse(int schemaVersion, List<Method> methods) {
    public NlpResponse { methods = List.copyOf(methods); }
    public record Method(String methodId, String status, String error, Provenance provenance,
                         List<Item> items, double latencyMillis) {
        public Method { items = List.copyOf(items); }
    }
    public record Provenance(String modelId, String revision, String implementationVersion,
                             String configSha256, String device, Map<String, String> libraries,
                             String scoreSemantics, String segmentation, Map<String, String> parameters) {
        public Provenance { libraries = Map.copyOf(libraries); parameters = Map.copyOf(parameters); }
    }
    public record Item(long turnId, String targetId, String status, String error, List<Chunk> chunks,
                       double latencyMillis) {
        public Item { chunks = List.copyOf(chunks); }
    }
    public record Chunk(int sentenceIndex, int start, int end, String text, String label,
                        Map<String, Double> scores, Double compound, Uncertainty uncertainty) {
        public Chunk { scores = Map.copyOf(scores); }
    }
    public record Uncertainty(double maxScore, double margin, double entropy, boolean abstained) { }
}
