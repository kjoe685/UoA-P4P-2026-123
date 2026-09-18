package engine.prompt;

import engine.agent.AdversarialStrategy;
import engine.config.PartyProfile;
import engine.config.ConfigurationSnapshot;
import java.util.List;
import java.util.Map;

/** Assembles one recipient's instructions; never renders the complete run configuration. */
public final class PromptManager {
    private final ConfigurationSnapshot snapshot;

    public PromptManager(ConfigurationSnapshot snapshot) { this.snapshot = snapshot; }

    public String assemblePersonaPrompt(String agentName, PartyProfile profile,
                                        AdversarialStrategy strategy, List<String> excerpts) {
        StringBuilder prompt = new StringBuilder(snapshot.template(TemplateName.BASE).render(Map.of()));
        prompt.append("\n\n").append(snapshot.template(TemplateName.PERSONA).render(Map.of(
                "AGENT_NAME", agentName, "PARTY_NAME", profile.displayName(), "IDEOLOGY", profile.ideology())));
        if (!excerpts.isEmpty()) {
            prompt.append("\n\n").append(snapshot.template(TemplateName.GROUNDING).render(Map.of(
                    "PARTY_NAME", profile.displayName(), "EXCERPTS", String.join("\n\n", excerpts))));
        }
        if (strategy != AdversarialStrategy.NONE) {
            prompt.append("\n\n").append(snapshot.template(TemplateName.valueOf(strategy.name())).render(Map.of()));
        }
        return prompt.toString();
    }

    public String cue(TemplateName name, String topic) {
        if (!List.of(TemplateName.OPENING, TemplateName.NEW_TOPIC, TemplateName.FOLLOW_UP,
                TemplateName.INTERJECTION, TemplateName.TOPIC_ANNOUNCEMENT).contains(name)) {
            throw new IllegalArgumentException("Not a public turn template");
        }
        return snapshot.template(name).render(Map.of("TOPIC", topic));
    }
}
