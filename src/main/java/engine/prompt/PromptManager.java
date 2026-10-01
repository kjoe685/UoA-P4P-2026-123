package engine.prompt;

import engine.agent.AdversarialStrategy;
import engine.config.PartyProfile;
import engine.config.ConfigurationSnapshot;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import engine.transcript.*;

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

    /** Targets and position history come exclusively from this topic's public evidence. */
    public String turnCue(TemplateName name,Topic topic,Participant recipient,List<PublicEvent> evidence) {
        String target="NONE"; List<String> own=new ArrayList<>();
        for (var event:List.copyOf(evidence)) {
            if (!topic.id().equals(event.topicId()) || event.speaker()==null) continue;
            if (recipient.id().equals(event.speaker().id())) own.add(event.id());
            else target=event.id();
        }
        List<String> history=new ArrayList<>();
        if (!own.isEmpty()) {
            history.add(own.get(0));
            for (int index=Math.max(1,own.size()-2);index<own.size();index++) history.add(own.get(index));
        }
        return cue(name,topic.title())+"\n\n"+snapshot.template(TemplateName.ENGAGEMENT).render(Map.of(
                "TOPIC_ID",topic.id(),"TARGET_TURN_ID",target,"OWN_TURN_IDS",history.isEmpty() ? "NONE" : String.join(", ",history)));
    }
}
