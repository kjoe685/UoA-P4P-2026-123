package engine.transcript;

public record Topic(String id, String title, String policyTarget) {
    public Topic {
        if (id == null || id.isBlank() || title == null || title.isBlank()) throw new IllegalArgumentException("Invalid topic");
    }
}
