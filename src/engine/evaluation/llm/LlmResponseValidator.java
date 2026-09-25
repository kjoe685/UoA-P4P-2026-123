package engine.evaluation.llm;

import engine.chat.OutputSchema;
import engine.evaluation.EvaluationStatus;
import engine.transcript.DebateEvent;
import engine.transcript.Participant;
import engine.utils.Json;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Coverage and evidence validation remain mandatory even with provider structured output. */
public final class LlmResponseValidator {
    private LlmResponseValidator() { }
    public static LlmAssessment parse(String response, int topic, List<DebateEvent> events,
                                      List<Participant> participants, LlmRubric rubric) {
        try {
            LlmAssessment result = Json.read(response, LlmAssessment.class);
            if (result.topicIndex() != topic) throw new IllegalArgumentException();
            Set<String> expected = participants.stream().map(Participant::id).collect(Collectors.toSet());
            var turns = events.stream().filter(e -> e.speaker() != null).collect(Collectors.toMap(DebateEvent::turnId, e -> e));
            var definitions = rubric.metrics().stream().collect(Collectors.toMap(LlmRubric.Metric::id, m -> m));
            Set<String> seen = new HashSet<>();
            for (var participant : result.participants()) {
                if (!expected.contains(participant.participantId()) || !seen.add(participant.participantId())) throw new IllegalArgumentException();
                Set<String> metricIds = new HashSet<>();
                for (var metric : participant.metrics()) {
                    var definition = definitions.get(metric.metricId());
                    if (definition == null || !metricIds.add(metric.metricId()) || metric.explanation() == null
                            || metric.explanation().isBlank() || metric.explanation().length() > 4000
                            || metric.evidenceTurnIds().size() > 100
                            || new HashSet<>(metric.evidenceTurnIds()).size() != metric.evidenceTurnIds().size()) throw new IllegalArgumentException();
                    int ownEvidence = 0;
                    for (Long id : metric.evidenceTurnIds()) {
                        var event = turns.get(id);
                        if (event == null || event.topicIndex() != topic) throw new IllegalArgumentException();
                        if (event.speaker().id().equals(participant.participantId())) ownEvidence++;
                    }
                    if (metric.status() == EvaluationStatus.OK) {
                        if (metric.score() == null || metric.score() < definition.minimum() || metric.score() > definition.maximum()
                                || ownEvidence < definition.minimumParticipantTurns()) throw new IllegalArgumentException();
                        if (definition.requiresPriorOtherSpeaker()) {
                            boolean exchange = metric.evidenceTurnIds().stream().map(turns::get)
                                    .filter(e -> !e.speaker().id().equals(participant.participantId()))
                                    .anyMatch(other -> metric.evidenceTurnIds().stream().map(turns::get)
                                            .anyMatch(own -> own.speaker().id().equals(participant.participantId()) && own.turnId() > other.turnId()));
                            if (!exchange) throw new IllegalArgumentException();
                        }
                    } else if (metric.status() != EvaluationStatus.INSUFFICIENT_EVIDENCE || metric.score() != null) throw new IllegalArgumentException();
                }
                if (!metricIds.equals(definitions.keySet())) throw new IllegalArgumentException();
            }
            if (!seen.equals(expected)) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid LLM assessment schema, coverage, score or evidence"); }
    }
    public static OutputSchema schema(List<Participant> participants, LlmRubric rubric) {
        var metric = object(Map.of(
                "metricId", Map.of("type", "string", "enum", rubric.metrics().stream().map(LlmRubric.Metric::id).toList()),
                "status", Map.of("type", "string", "enum", List.of("OK", "INSUFFICIENT_EVIDENCE")),
                "score", Map.of("type", List.of("integer", "null")),
                "explanation", Map.of("type", "string"),
                "evidenceTurnIds", Map.of("type", "array", "items", Map.of("type", "integer"))));
        var participant = object(Map.of("participantId", Map.of("type", "string", "enum", participants.stream().map(Participant::id).toList()),
                "metrics", Map.of("type", "array", "items", metric)));
        return new OutputSchema("debate_assessment", Json.writeCanonical(object(Map.of("topicIndex", Map.of("type", "integer"),
                "participants", Map.of("type", "array", "items", participant)))));
    }
    private static Map<String, Object> object(Map<String, Object> fields) {
        // Sorted fields keep fallback prompts and reports reproducible between JVM invocations.
        var sorted = new java.util.TreeMap<>(fields);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object"); schema.put("properties", sorted);
        schema.put("required", List.copyOf(sorted.keySet())); schema.put("additionalProperties", false);
        return schema;
    }
}
