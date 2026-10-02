package engine.evaluation.llm;

import engine.transcript.Participant;
import engine.transcript.PublicEvent;
import java.util.List;

/** Public evidence availability only. Presence is not a score or a substantive judgment. */
public final class LlmEvidenceEligibility {
    private LlmEvidenceEligibility() { }
    public record PriorExchange(String otherTurnId, String responseTurnId) { }
    public record ParticipantAvailability(String participantId, int ownTurnCount,
                                           List<String> insufficientEvidenceMetrics, PriorExchange latestPriorExchange) {
        public ParticipantAvailability { insufficientEvidenceMetrics = List.copyOf(insufficientEvidenceMetrics); }
    }
    public static List<ParticipantAvailability> forTopic(String topicId, List<PublicEvent> events,
                                                         List<Participant> participants, LlmRubric rubric) {
        var speeches = events.stream().filter(event -> topicId.equals(event.topicId()) && event.speaker() != null).toList();
        return participants.stream().map(participant -> {
            int ownCount = 0;
            PublicEvent lastOther = null;
            PriorExchange lastExchange = null;
            for (var event : speeches) {
                if (event.speaker().id().equals(participant.id())) {
                    ownCount++;
                    if (lastOther != null) lastExchange = new PriorExchange(lastOther.id(), event.id());
                } else lastOther = event;
            }
            final int availableOwnTurns = ownCount;
            final boolean priorExchangePresent = lastExchange != null;
            var insufficient = rubric.metrics().stream().filter(metric -> availableOwnTurns < metric.minimumParticipantTurns()
                    || metric.requiresPriorOtherSpeaker() && !priorExchangePresent).map(LlmRubric.Metric::id).toList();
            return new ParticipantAvailability(participant.id(), ownCount, insufficient, lastExchange);
        }).toList();
    }
}
