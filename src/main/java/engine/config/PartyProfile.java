package engine.config;

public record PartyProfile(String displayName, String ideology) {
    public PartyProfile {
        if (displayName == null || displayName.isBlank() || ideology == null || ideology.isBlank()) {
            throw new IllegalArgumentException("Party name and ideology are required");
        }
    }
}
