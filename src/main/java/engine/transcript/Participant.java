package engine.transcript;

import engine.agent.Party;
public record Participant(String id, String name, Party party, String partyName) {
    public Participant {
        if (id == null || id.isBlank() || name == null || name.isBlank() || party == null || partyName == null || partyName.isBlank())
            throw new IllegalArgumentException("Invalid participant identity");
    }
}
