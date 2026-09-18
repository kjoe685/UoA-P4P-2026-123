package engine.prompt;

import java.util.Set;

public enum TemplateName {
    BASE("BasePrompt.txt"),
    PERSONA("PoliticianPrompt.txt", "AGENT_NAME", "PARTY_NAME", "IDEOLOGY"),
    GROUNDING("GroundingPrompt.txt", "PARTY_NAME", "EXCERPTS"),
    OPENING("OpeningCue.txt", "TOPIC"),
    NEW_TOPIC("NewTopicCue.txt", "TOPIC"),
    FOLLOW_UP("FollowUpCue.txt", "TOPIC"),
    INTERJECTION("InterjectionCue.txt", "TOPIC"),
    TOPIC_ANNOUNCEMENT("TopicAnnouncement.txt", "TOPIC"),
    TOPIC_DERAILMENT("strategies/TopicDerailment.txt"),
    STRAW_MAN("strategies/StrawMan.txt"),
    PROCEDURAL_MANIPULATION("strategies/ProceduralManipulation.txt");

    private final String fileName;
    private final Set<String> placeholders;

    TemplateName(String fileName, String... placeholders) {
        this.fileName = fileName;
        this.placeholders = Set.of(placeholders);
    }

    public String fileName() { return fileName; }
    public Set<String> placeholders() { return placeholders; }
}
