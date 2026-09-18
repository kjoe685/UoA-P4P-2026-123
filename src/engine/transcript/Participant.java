package engine.transcript;

/** Public identity only: never add persona instructions, model settings, or hidden assignments here. */
public record Participant(String id, String name, String party) {
    public Participant {
        if (id == null || id.isBlank() || name == null || name.isBlank() || party == null || party.isBlank()) {
            throw new IllegalArgumentException("Participant id, name, and party are required");
        }
    }
}
