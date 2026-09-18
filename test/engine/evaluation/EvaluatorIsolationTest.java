package engine.evaluation;

import engine.TestFixtures;
import engine.agent.AdversarialStrategy;
import engine.agent.Party;
import engine.chat.ChatRequest;
import engine.prompt.PromptTemplate;
import engine.transcript.DebateEvent;
import engine.transcript.EventType;
import engine.transcript.Transcript;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EvaluatorIsolationTest {
    @Test void evaluationCallsAreIndependentAndNeverBecomeAgentHistory() {
        var requests = new ArrayList<ChatRequest>();
        engine.ChatManager sharedProvider = request -> {
            requests.add(request);
            return TestFixtures.response("{\"metrics\":{\"comment\":\"EVALUATION_RESULT_SENTINEL\"}}");
        };
        var evaluator = new LLMEvaluator("test", sharedProvider, TestFixtures.model(),
                new PromptTemplate("rubric", "EVALUATOR_PRIVATE_SENTINEL", Set.of()),
                new PromptTemplate("cue", "Evaluate public evidence", Set.of()));
        var transcript = new Transcript(List.of(new DebateEvent(1, 0, "Housing", 0,
                EventType.TOPIC_ANNOUNCEMENT, null, "Public announcement")));
        var result = evaluator.evaluate(transcript);
        assertEquals("EVALUATION_RESULT_SENTINEL", ((TextMetric) result.getMetrics().get("comment")).getValue());
        evaluator.evaluate(Transcript.empty());
        assertFalse(Json.write(requests.get(1)).contains("Public announcement"));

        var agent = TestFixtures.agent(Party.LABOUR, AdversarialStrategy.NONE, sharedProvider);
        agent.speak(transcript, "Speak");
        String agentRequest = Json.write(requests.get(2));
        assertFalse(agentRequest.contains("EVALUATOR_PRIVATE_SENTINEL"));
        assertFalse(agentRequest.contains("EVALUATION_RESULT_SENTINEL"));
        assertTrue(agentRequest.contains("Public announcement"));
    }
}
