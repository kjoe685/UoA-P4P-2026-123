package engine.debate;

import engine.TestFixtures;
import engine.agent.AdversarialStrategy;
import engine.agent.Party;
import engine.config.InterruptionConfig;
import engine.prompt.PromptManager;
import engine.transcript.DebateEvent;
import engine.transcript.EventType;
import engine.transcript.Transcript;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DebateManagerTest {
    private DebateManager debate(double chance, long seed, List<DebateEvent> displayed) {
        var provider = new TestFixtures.RecordingProvider();
        var agents = List.of(TestFixtures.agent(Party.LABOUR, AdversarialStrategy.NONE, provider),
                TestFixtures.agent(Party.NATIONAL, AdversarialStrategy.TOPIC_DERAILMENT, provider),
                TestFixtures.agent(Party.GREEN, AdversarialStrategy.NONE, provider));
        return new DebateManager(agents, List.of("Housing", "Climate"), 2, displayed::add,
                new PromptManager(TestFixtures.snapshot()), new InterruptionConfig(chance, chance, seed));
    }

    @Test void canonicalTranscriptIncludesAnnouncementsSpeechesAndSingleInterjections() {
        var displayed = new ArrayList<DebateEvent>();
        var debate = debate(1, 17, displayed);
        Transcript before = debate.transcript();
        Transcript result = debate.run();
        assertTrue(before.events().isEmpty());
        assertEquals(displayed, result.events());
        assertEquals(26, result.events().size());
        long expectedId = 1;
        for (int i = 0; i < result.events().size(); i++) {
            var event = result.events().get(i);
            assertEquals(expectedId++, event.turnId());
            if (event.type() == EventType.INTERJECTION) {
                var previous = result.events().get(i - 1);
                assertEquals(EventType.SPEECH, previous.type());
                assertNotEquals(previous.speaker().id(), event.speaker().id());
                assertEquals(previous.round(), event.round());
                assertEquals(previous.topicIndex(), event.topicIndex());
            }
        }
        assertEquals(EventType.TOPIC_ANNOUNCEMENT, result.events().get(0).type());
        assertEquals("Climate", result.events().get(13).topic());
        assertThrows(UnsupportedOperationException.class, () -> result.events().clear());
        assertThrows(IllegalStateException.class, debate::run);
    }

    @Test void interruptionSchedulingIsReproducible() {
        assertEquals(debate(0.4, 92, new ArrayList<>()).run(), debate(0.4, 92, new ArrayList<>()).run());
        assertEquals(14, debate(0, 92, new ArrayList<>()).run().events().size());
    }

    @Test void exportHasOnlyAllowlistedPublicFieldsAndRoundTrips() {
        Transcript result = debate(0, 1, new ArrayList<>()).run();
        String json = Json.write(result);
        assertEquals(result, Json.read(json, Transcript.class));
        Map<?, ?> root = (Map<?, ?>) Json.parse(json);
        assertEquals(Set.of("events"), root.keySet());
        for (Object raw : (List<?>) root.get("events")) {
            Map<?, ?> event = (Map<?, ?>) raw;
            assertEquals(Set.of("turnId", "topicIndex", "topic", "round", "type", "speaker", "text"), event.keySet());
            if (event.get("speaker") != null) {
                assertEquals(Set.of("id", "name", "party"), ((Map<?, ?>) event.get("speaker")).keySet());
            }
        }
    }

    @Test void invalidTranscriptsAndDuplicateParticipantsFailEarly() {
        var event = new DebateEvent(2, 0, "Housing", 0, EventType.TOPIC_ANNOUNCEMENT, null, "Opening");
        assertThrows(IllegalArgumentException.class, () -> new Transcript(List.of(event)));
        var agent = TestFixtures.agent(Party.LABOUR, AdversarialStrategy.NONE, new TestFixtures.RecordingProvider());
        assertThrows(IllegalArgumentException.class, () -> new DebateManager(List.of(agent, agent), List.of("Housing"), 1,
                ignored -> { }, new PromptManager(TestFixtures.snapshot()), new InterruptionConfig(0, 0, 1)));
    }
}
