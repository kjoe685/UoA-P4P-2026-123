package engine.evaluation.local;

import engine.utils.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record LocalEvaluationConfig(int schemaVersion, URI endpoint, int timeoutSeconds, int batchSize,
                                    List<String> methods, List<PolicyTarget> policyTargets) {
    public static final Set<String> METHODS = Set.of("vader-sentiment", "cardiff-sentiment", "deberta-stance");

    public LocalEvaluationConfig {
        methods = List.copyOf(methods);
        policyTargets = List.copyOf(policyTargets);
        if (schemaVersion != 1 || timeoutSeconds < 1 || timeoutSeconds > 3600 || batchSize < 1 || batchSize > 100
                || methods.isEmpty() || !METHODS.containsAll(methods) || new HashSet<>(methods).size() != methods.size()) {
            throw new IllegalArgumentException("Invalid local evaluation settings");
        }
        if (endpoint == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid private NLP endpoint");
        }
        Set<String> ids = new HashSet<>();
        for (PolicyTarget target : policyTargets) {
            if (!ids.add(target.id())) throw new IllegalArgumentException("Duplicate policy target ID");
            if (policyTargets.stream().filter(other -> other.topicIndex() == target.topicIndex()).count() > 20)
                throw new IllegalArgumentException("At most 20 policy targets per topic");
        }
    }

    public record PolicyTarget(String id, int topicIndex, String proposition) {
        public PolicyTarget {
            if (id == null || id.isBlank() || id.length() > 2000 || topicIndex < 0
                    || proposition == null || proposition.isBlank() || proposition.length() > 2000) {
                throw new IllegalArgumentException("Policy targets need an ID, topic index, and explicit proposition");
            }
        }
    }

    public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }

    public static LocalEvaluationConfig load(Path path) {
        try {
            return Json.read(Files.readString(path), LocalEvaluationConfig.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read local evaluation configuration");
        }
    }
}
