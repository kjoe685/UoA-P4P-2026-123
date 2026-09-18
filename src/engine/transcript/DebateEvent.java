package engine.transcript;

import java.util.Objects;

/** Allowlisted public payload. There is deliberately no arbitrary metadata or private-context field. */
public record DebateEvent(long turnId, int topicIndex, String topic, int round,
                          EventType type, Participant speaker, String text) {
    public DebateEvent {
        Objects.requireNonNull(type, "type");
        if (turnId < 1 || topicIndex < 0 || topic == null || topic.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Invalid public debate event");
        }
        if (type == EventType.TOPIC_ANNOUNCEMENT) {
            if (speaker != null || round != 0) throw new IllegalArgumentException("Announcements have no participant or round");
        } else if (speaker == null || round < 1) {
            throw new IllegalArgumentException("Speeches and interjections require a participant and round");
        }
    }
}
