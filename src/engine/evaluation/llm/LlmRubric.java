package engine.evaluation.llm;

import java.util.List;
import java.util.Map;
import java.util.HashSet;

/** Metric semantics, scales and evidence thresholds are versioned external data. */
public record LlmRubric(String version, List<Metric> metrics) {
    public LlmRubric {
        metrics = List.copyOf(metrics);
        if (version == null || version.isBlank() || metrics.isEmpty() || metrics.size() > 20)
            throw new IllegalArgumentException("Invalid LLM rubric");
        var ids = new HashSet<String>();
        for (var metric : metrics) if (!ids.add(metric.id())) throw new IllegalArgumentException("Duplicate rubric metric");
    }
    public record Metric(String id, String description, int minimum, int maximum,
                         int minimumParticipantTurns, boolean requiresPriorOtherSpeaker, Map<String, String> anchors) {
        public Metric {
            anchors = Map.copyOf(anchors);
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}") || description == null || description.isBlank()
                    || minimum < 0 || maximum <= minimum || maximum > 100 || minimumParticipantTurns < 1
                    || minimumParticipantTurns > 20 || anchors.size() != maximum - minimum + 1)
                throw new IllegalArgumentException("Invalid rubric metric");
            for (int score = minimum; score <= maximum; score++) {
                String anchor = anchors.get(Integer.toString(score));
                if (anchor == null || anchor.isBlank()) throw new IllegalArgumentException("Every rubric score needs an anchor");
            }
        }
    }
}
