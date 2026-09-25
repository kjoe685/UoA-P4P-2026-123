package engine.evaluation;

import engine.TestFixtures;
import engine.agent.AdversarialStrategy;
import engine.agent.Party;
import engine.chat.ChatRequest;
import engine.evaluation.llm.LlmAnalysisMetric;
import engine.evaluation.llm.LlmEvaluationResources;
import engine.transcript.Transcript;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class EvaluatorIsolationTest {
    @Test void evaluationCallsAreIndependentAndNeverBecomeAgentHistory() {
        var requests = new ArrayList<ChatRequest>();
        engine.ChatManager sharedProvider = request -> {
            requests.add(request);
            return TestFixtures.response(request.systemInstructions().contains("EVALUATOR_PRIVATE_SENTINEL")
                    ? LlmFixtures.valid(request) : "Public speech");
        };
        var source = LlmFixtures.resources();
        var resources = new LlmEvaluationResources(source.config(), source.rubric(),
                "EVALUATOR_PRIVATE_SENTINEL\n" + source.systemPrompt(), source.cue(), source.repairPrompt(), source.sourceHashes());
        var evaluator = new LLMEvaluator(sharedProvider, TestFixtures.model(), resources);
        var transcript = LlmFixtures.transcript();
        var result = evaluator.evaluate(transcript);
        assertEquals(EvaluationStatus.OK, result.getStatus());
        assertEquals(2, ((LlmAnalysisMetric) result.getMetrics().get("assessments")).topics().size());
        assertFalse(requests.get(1).messages().get(0).content().contains("Build public housing"));
        assertFalse(Json.write(requests.get(1)).contains("EVALUATION_RESULT_SENTINEL"));
        evaluator.evaluate(Transcript.empty());
        assertEquals(2, requests.size());

        var agent = TestFixtures.agent(Party.LABOUR, AdversarialStrategy.NONE, sharedProvider);
        agent.speak(transcript, "Speak");
        String agentRequest = Json.write(requests.get(2));
        assertFalse(agentRequest.contains("EVALUATOR_PRIVATE_SENTINEL"));
        assertFalse(agentRequest.contains("EVALUATION_RESULT_SENTINEL"));
        assertTrue(agentRequest.contains("Build public housing"));
        assertNull(requests.get(2).outputSchema());
    }
}
