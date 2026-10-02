package engine.evaluation.llm;

import engine.chat.OutputSchema;
import engine.evaluation.EvaluationStatus;
import engine.transcript.PublicEvent;
import engine.transcript.Participant;
import engine.utils.Json;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Coverage and evidence validation remain mandatory even with provider structured output. */
public final class LlmResponseValidator {
    private LlmResponseValidator() { }
    /** Fixed application rules only; never copy candidate text or parser exception details. */
    public enum ValidationCode { STRUCTURE_OR_EVIDENCE, SCORE_OR_STATUS, MINIMUM_OWN_TURNS, PRIOR_EXCHANGE }
    public static final class ValidationException extends IllegalArgumentException {
        private final List<ValidationCode> codes;
        private ValidationException(List<ValidationCode> codes) {
            super("Invalid LLM assessment schema, coverage, score or evidence");
            this.codes = List.copyOf(codes);
        }
        public List<ValidationCode> codes() { return codes; }
    }
    public static LlmAssessment parse(String response, String topic, List<PublicEvent> events,
                                      List<Participant> participants, LlmRubric rubric) {
        try {
            LlmAssessment result = Json.read(response, LlmAssessment.class);
            if (!topic.equals(result.topicId())) throw new IllegalArgumentException();
            Set<String> expected = participants.stream().map(Participant::id).collect(Collectors.toSet());
            var turns = events.stream().filter(e -> e.speaker() != null).collect(Collectors.toMap(PublicEvent::id, e -> e));
            var definitions = rubric.metrics().stream().collect(Collectors.toMap(LlmRubric.Metric::id, m -> m));
            Set<String> seen = new HashSet<>();
            var failures = EnumSet.noneOf(ValidationCode.class);
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
                    for (String id : metric.evidenceTurnIds()) {
                        var event = turns.get(id);
                        if (event == null || !topic.equals(event.topicId())) throw new IllegalArgumentException();
                        if (event.speaker().id().equals(participant.participantId())) ownEvidence++;
                    }
                    if (metric.status() == EvaluationStatus.OK) {
                        if (metric.score() == null || metric.score() < definition.minimum() || metric.score() > definition.maximum())
                            failures.add(ValidationCode.SCORE_OR_STATUS);
                        if (ownEvidence < definition.minimumParticipantTurns()) failures.add(ValidationCode.MINIMUM_OWN_TURNS);
                        if (definition.requiresPriorOtherSpeaker()) {
                            boolean exchange = metric.evidenceTurnIds().stream().map(turns::get)
                                    .filter(e -> !e.speaker().id().equals(participant.participantId()))
                                    .anyMatch(other -> metric.evidenceTurnIds().stream().map(turns::get)
                                            .anyMatch(own -> own.speaker().id().equals(participant.participantId()) && events.indexOf(own) > events.indexOf(other)));
                            if (!exchange) failures.add(ValidationCode.PRIOR_EXCHANGE);
                        }
                    } else if (metric.status() != EvaluationStatus.INSUFFICIENT_EVIDENCE || metric.score() != null)
                        failures.add(ValidationCode.SCORE_OR_STATUS);
                }
                if (!metricIds.equals(definitions.keySet())) throw new IllegalArgumentException();
            }
            if (!seen.equals(expected)) throw new IllegalArgumentException();
            if (!failures.isEmpty()) throw new ValidationException(List.copyOf(failures));
            return result;
        } catch (ValidationException e) { throw e; }
        catch (RuntimeException e) { throw new ValidationException(List.of(ValidationCode.STRUCTURE_OR_EVIDENCE)); }
    }
    public static OutputSchema schema(List<Participant> participants, LlmRubric rubric) {
        var metric = object(Map.of(
                "metricId", Map.of("type", "string", "enum", rubric.metrics().stream().map(LlmRubric.Metric::id).toList()),
                "status", Map.of("type", "string", "enum", List.of("OK", "INSUFFICIENT_EVIDENCE")),
                "score", Map.of("type", List.of("integer", "null")),
                "explanation", Map.of("type", "string"),
                "evidenceTurnIds", Map.of("type", "array", "items", Map.of("type", "string"))));
        var participant = object(Map.of("participantId", Map.of("type", "string", "enum", participants.stream().map(Participant::id).toList()),
                "metrics", Map.of("type", "array", "items", metric)));
        return new OutputSchema("debate_assessment", Json.writeCanonical(object(Map.of("topicId", Map.of("type", "string"),
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
