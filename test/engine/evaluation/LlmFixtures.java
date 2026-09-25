package engine.evaluation;

import engine.chat.ChatRequest;
import engine.evaluation.llm.*;
import engine.transcript.*;
import engine.utils.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class LlmFixtures {
    static final Participant A = new Participant("A", "Alpha", "Party A");
    static final Participant B = new Participant("B", "Beta", "Party B");
    static LlmEvaluationResources resources() { return LlmEvaluationResources.load(Path.of("resources")); }
    static Transcript transcript() {
        return new Transcript(List.of(
                new DebateEvent(1, 0, "Housing", 0, EventType.TOPIC_ANNOUNCEMENT, null, "Housing discussion"),
                new DebateEvent(2, 0, "Housing", 1, EventType.SPEECH, A, "Build public housing to increase supply."),
                new DebateEvent(3, 0, "Housing", 1, EventType.SPEECH, B, "How will Alpha fund it?"),
                new DebateEvent(4, 0, "Housing", 2, EventType.SPEECH, A, "I would fund construction from a land tax."),
                new DebateEvent(5, 1, "Climate", 0, EventType.TOPIC_ANNOUNCEMENT, null, "Climate discussion"),
                new DebateEvent(6, 1, "Climate", 1, EventType.SPEECH, B, "Invest in renewable generation.")));
    }
    static String valid(ChatRequest request) {
        JsonNode input = Json.read(request.messages().get(0).content(), JsonNode.class);
        int topic = input.path("topicIndex").intValue();
        List<LlmAssessment.ParticipantAssessment> participants = new ArrayList<>();
        for (var participant : input.path("participants")) {
            String id = participant.path("id").textValue();
            List<Long> own = new ArrayList<>(), other = new ArrayList<>();
            for (var event : input.path("events")) {
                if (event.path("speaker").isNull()) continue;
                (id.equals(event.path("speaker").path("id").asText()) ? own : other).add(event.path("turnId").longValue());
            }
            List<LlmAssessment.MetricAssessment> metrics = new ArrayList<>();
            for (var metric : resources().rubric().metrics()) {
                boolean enough = own.size() >= metric.minimumParticipantTurns();
                Long earlier = other.stream().filter(turn -> own.stream().anyMatch(later -> later > turn)).findFirst().orElse(null);
                if (metric.requiresPriorOtherSpeaker() && earlier == null) enough = false;
                var evidence = new ArrayList<Long>();
                if (enough) { evidence.addAll(own); if (metric.requiresPriorOtherSpeaker()) evidence.add(earlier); }
                metrics.add(new LlmAssessment.MetricAssessment(metric.id(), enough ? EvaluationStatus.OK : EvaluationStatus.INSUFFICIENT_EVIDENCE,
                        enough ? 3 : null, enough ? "EVALUATION_RESULT_SENTINEL: supported by the exchange." : "Not enough observed evidence.", evidence));
            }
            participants.add(new LlmAssessment.ParticipantAssessment(id, metrics));
        }
        return Json.write(new LlmAssessment(topic, participants));
    }
}
