package engine.debate;

import engine.TestFixtures;
import engine.agent.*;
import engine.chat.ChatResponse;
import engine.config.ModelConfig;
import engine.prompt.*;
import engine.transcript.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EngagementCueTest {
    @TempDir Path root;
    private final Participant own=new Participant("labour","Labour MP",Party.LABOUR,"Labour");
    private final Participant other=new Participant("national","National MP",Party.NATIONAL,"National");
    private PublicEvent speech(String id,String topic,Participant speaker,String text) { return new PublicEvent(id,topic,PublicEvent.Type.SPEECH,speaker,text); }
    @Test void currentTopicTargetsLatestOtherContributionAndRetainsOpeningAndRecentOwnPositions() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var prompts=new PromptManager(snapshot); var topic=new Topic("housing","Housing",null);
        List<PublicEvent> evidence=List.of(speech("OTHER_TOPIC_SENTINEL","transport",other,"Prior topic only"),
                speech("opening","housing",own,"I support affordable public housing."),
                speech("challenge","housing",other,"How will you pay for that promise?"),
                speech("OMITTED_POSITION_SENTINEL","housing",own,"I would phase construction."),
                speech("recent-1","housing",own,"We can use existing appropriations."),
                speech("recent-2","housing",own,"I accept a staged timeline if costs rise."),
                new PublicEvent("chair","housing",PublicEvent.Type.CHAIR_RULING,null,"Order!"));
        String cue=prompts.turnCue(TemplateName.FOLLOW_UP,topic,own,evidence);
        assertTrue(cue.contains("Reply target: challenge")); assertTrue(cue.contains("opening, recent-1, recent-2"));
        assertFalse(cue.contains("OMITTED_POSITION_SENTINEL")); assertFalse(cue.contains("OTHER_TOPIC_SENTINEL")); assertTrue(cue.contains("reasoned concession"));
        var interjection=new PublicEvent("interjection","housing",PublicEvent.Type.INTERJECTION,other,"That timeline still risks overspending.");
        var latest=new ArrayList<>(evidence); latest.add(interjection);
        assertTrue(prompts.turnCue(TemplateName.INTERJECTION,topic,own,latest).contains("Reply target: interjection"));
        String transition=prompts.turnCue(TemplateName.NEW_TOPIC,new Topic("climate","Climate",null),own,evidence);
        assertTrue(transition.contains("Reply target: NONE")); assertTrue(transition.contains("contributions on this topic: NONE"));
    }
    @Test void fixedChallengeConcessionAndTacticFixtureUsesPublicPointersAndOnlyRecipientsPrivatePersona() throws Exception {
        var snapshot=TestFixtures.copyResources(root); var prompts=new PromptManager(snapshot); var topic=new Topic("housing","Housing",null);
        var model=new ModelConfig("openai","fake",null,null,1024,5);
        var evidence=List.of(speech("opening","housing",own,"We should build public housing immediately."),
                speech("challenge","housing",other,"Construction capacity limits how fast we can build."));
        var ordinary=new Agent(own,AdversarialStrategy.NONE,request -> {
            assertTrue(request.messages().get(request.messages().size()-1).content().contains("Reply target: challenge"));
            assertTrue(request.messages().stream().anyMatch(message -> message.content().contains("Construction capacity")));
            assertFalse(request.systemInstructions().contains("STRATEGY_SENTINEL"));
            return new ChatResponse("I accept the member's capacity concern and would phase delivery while retaining our housing goal.","fake","fake",ChatResponse.CompletionStatus.COMPLETED,null,0);
        },prompts.assemblePersonaPrompt("Labour MP",snapshot.config().parties().get(Party.LABOUR),AdversarialStrategy.NONE,List.of()),List.of(),model);
        assertTrue(ordinary.speak(evidence,prompts.turnCue(TemplateName.FOLLOW_UP,topic,own,evidence)).contains("phase delivery"));
        for (var tactic:List.of(AdversarialStrategy.TOPIC_DERAILMENT,AdversarialStrategy.STRAW_MAN,AdversarialStrategy.PROCEDURAL_MANIPULATION)) {
            String persona=prompts.assemblePersonaPrompt("National MP",new engine.config.PartyProfile("National","centre-right"),tactic,List.of());
            assertTrue(persona.contains("cited"));
            assertFalse(prompts.turnCue(TemplateName.FOLLOW_UP,topic,other,evidence).contains(tactic.name()));
        }
    }
}
