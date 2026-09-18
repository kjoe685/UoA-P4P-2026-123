package engine.agent;

import engine.TestFixtures;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.config.InterruptionConfig;
import engine.debate.DebateManager;
import engine.prompt.PromptManager;
import engine.transcript.Participant;
import engine.transcript.Transcript;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentIsolationTest {
    @Test void eachRequestContainsOnlyItsOwnSecretsAndPublicEvents() {
        var provider = new TestFixtures.RecordingProvider();
        Agent ordinary = new Agent(new Participant("a", "Ordinary MP", "Labour"), AdversarialStrategy.NONE,
                provider, "ORDINARY_PRIVATE_SENTINEL", List.of("ORDINARY_GROUNDING_SENTINEL"), TestFixtures.model());
        Agent disruptive = new Agent(new Participant("b", "Other MP", "National"), AdversarialStrategy.STRAW_MAN,
                provider, "HIDDEN_STRATEGY_SENTINEL", List.of("OTHER_GROUNDING_SENTINEL"), TestFixtures.model());
        var debate = new DebateManager(List.of(ordinary, disruptive), List.of("Housing", "Climate"), 2,
                event -> { }, new PromptManager(TestFixtures.snapshot()), new InterruptionConfig(0, 0, 7));
        Transcript transcript = debate.run();
        assertEquals(8, provider.requests.size());
        for (int i = 0; i < provider.requests.size(); i++) {
            ChatRequest request = provider.requests.get(i);
            assertEquals(i % 2 == 0 ? "ORDINARY_PRIVATE_SENTINEL" : "HIDDEN_STRATEGY_SENTINEL", request.systemInstructions());
            String messages = Json.write(request.messages());
            for (String secret : List.of("PRIVATE_SENTINEL", "STRATEGY_SENTINEL", "GROUNDING_SENTINEL", "STRAW_MAN")) {
                assertFalse(messages.contains(secret));
                assertFalse(Json.write(transcript).contains(secret));
            }
            assertTrue(messages.contains(i < 4 ? "Housing" : "Climate"));
        }
        assertTrue(provider.requests.get(2).messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.ASSISTANT));
        assertFalse(provider.requests.get(0).toString().contains("ORDINARY_PRIVATE_SENTINEL"));
        assertFalse(ordinary.toString().contains("PRIVATE_SENTINEL"));
        assertThrows(IllegalArgumentException.class, () -> Json.write(ordinary));
    }

    @Test void providerRequestsAreImmutableAndFailedOrStructuredMetadataIsNotBroadcast() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(ChatMessage.Role.USER, "public"));
        var request = new ChatRequest("private", messages, TestFixtures.model());
        messages.clear();
        assertEquals(1, request.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());

        Agent truncated = TestFixtures.agent(Party.LABOUR, AdversarialStrategy.NONE, ignored ->
                new ChatResponse("INCOMPLETE_PRIVATE_PAYLOAD", "mock", "mock", ChatResponse.CompletionStatus.TRUNCATED, null, 0));
        var debate = new DebateManager(List.of(truncated), List.of("Housing"), 1, event -> { },
                new PromptManager(TestFixtures.snapshot()), new InterruptionConfig(0, 0, 0));
        assertThrows(IllegalStateException.class, debate::run);
        assertEquals(1, debate.transcript().events().size());
        assertFalse(Json.write(debate.transcript()).contains("INCOMPLETE_PRIVATE_PAYLOAD"));
    }
}
