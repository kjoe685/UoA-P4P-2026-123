package engine.transcript;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Transcript(int schemaVersion, String runId, long startedAt, Long endedAt,
                         List<Participant> roster, List<Topic> topics, List<PublicEvent> events, Outcome outcome) {
    public enum Outcome { RUNNING, COMPLETE, ADJOURNED, ERROR, INTERRUPTED }
    public Transcript {
        if (schemaVersion != 2 || runId == null || runId.isBlank() || startedAt < 0 || outcome == null)
            throw new IllegalArgumentException("Invalid transcript header");
        roster = List.copyOf(roster); topics = List.copyOf(topics); events = List.copyOf(events);
        if (roster.isEmpty() || topics.isEmpty()) throw new IllegalArgumentException("Transcript needs a roster and topics");
        Set<String> participantIds = new HashSet<>(), topicIds = new HashSet<>(), eventIds = new HashSet<>();
        for (var member : roster) if (!participantIds.add(member.id())) throw new IllegalArgumentException("Duplicate participant");
        for (var topic : topics) if (!topicIds.add(topic.id())) throw new IllegalArgumentException("Duplicate topic");
        for (var event : events) {
            if (!eventIds.add(event.id()) || !topicIds.contains(event.topicId())) throw new IllegalArgumentException("Invalid evidence reference");
            if (event.speaker() != null && !roster.contains(event.speaker())) throw new IllegalArgumentException("Speaker outside roster");
        }
        if ((outcome == Outcome.RUNNING) != (endedAt == null) || endedAt != null && endedAt < startedAt)
            throw new IllegalArgumentException("Invalid transcript outcome/time");
    }
}
