package engine.transcript;

/** Allowlisted public evidence. Calling/progress and assignments never enter this record. */
public record PublicEvent(String id, String topicId, Type type, Participant speaker, String text) {
    public enum Type { TOPIC, CHAIR, CHAIR_RULING, SPEECH, INTERJECTION }
    public PublicEvent {
        if (id == null || id.isBlank() || topicId == null || topicId.isBlank() || type == null || text == null || text.isBlank())
            throw new IllegalArgumentException("Invalid public event");
        if ((type == Type.SPEECH || type == Type.INTERJECTION) != (speaker != null))
            throw new IllegalArgumentException("Participant speech needs an identity");
    }
}
