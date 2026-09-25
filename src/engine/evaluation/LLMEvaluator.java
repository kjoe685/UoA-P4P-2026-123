package engine.evaluation;

import engine.ChatManager;
import engine.chat.ChatMessage;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.config.ModelConfig;
import engine.evaluation.llm.*;
import engine.provider.ProviderCapabilities;
import engine.transcript.DebateEvent;
import engine.transcript.Participant;
import engine.transcript.Transcript;
import engine.utils.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One isolated assessment of all participants per topic, with at most one budgeted repair. */
public final class LLMEvaluator implements Evaluator {
    public static final String ID = "llm-rubric";
    private final ChatManager provider;
    private final ModelConfig model;
    private final LlmEvaluationResources resources;

    public LLMEvaluator(ChatManager provider, ModelConfig model, LlmEvaluationResources resources) {
        this.provider = Objects.requireNonNull(provider);
        this.model = Objects.requireNonNull(model);
        this.resources = Objects.requireNonNull(resources);
    }

    @Override public EvaluationResult evaluate(Transcript transcript) {
        Map<Integer, List<DebateEvent>> topics = new LinkedHashMap<>();
        Map<String, Participant> participants = new LinkedHashMap<>();
        for (var event : transcript.events()) {
            var events = topics.computeIfAbsent(event.topicIndex(), ignored -> new ArrayList<>());
            if (!events.isEmpty() && !events.get(0).topic().equals(event.topic()))
                throw new IllegalArgumentException("Inconsistent public topic identity");
            events.add(event);
            if (event.speaker() != null) {
                var previous = participants.putIfAbsent(event.speaker().id(), event.speaker());
                if (previous != null && !previous.equals(event.speaker()))
                    throw new IllegalArgumentException("Inconsistent public participant identity");
            }
        }
        List<Participant> roster = List.copyOf(participants.values());
        List<LlmAnalysisMetric.TopicResult> results = new ArrayList<>();
        Budget budget = new Budget();
        for (var topic : topics.entrySet()) results.add(evaluateTopic(topic.getKey(), topic.getValue(), roster, budget));
        boolean failed = results.stream().anyMatch(t -> t.status() == EvaluationStatus.FAILED);
        boolean scored = results.stream().anyMatch(t -> t.status() == EvaluationStatus.OK);
        return new EvaluationResult(ID, Map.of("assessments", new LlmAnalysisMetric(model, resources.rubric(), resources.sourceHashes(), results)),
                failed ? EvaluationStatus.FAILED : scored ? EvaluationStatus.OK : EvaluationStatus.INSUFFICIENT_EVIDENCE,
                failed ? "one_or_more_topics_failed" : null);
    }

    private LlmAnalysisMetric.TopicResult evaluateTopic(int topic, List<DebateEvent> events,
                                                       List<Participant> roster, Budget budget) {
        List<LlmAnalysisMetric.Attempt> attempts = new ArrayList<>();
        if (events.stream().noneMatch(e -> e.speaker() != null)) {
            var empty = new LlmAssessment(topic, roster.stream().map(p -> new LlmAssessment.ParticipantAssessment(p.id(),
                    resources.rubric().metrics().stream().map(m -> new LlmAssessment.MetricAssessment(m.id(),
                            EvaluationStatus.INSUFFICIENT_EVIDENCE, null, "No public contributions in this topic.", List.of())).toList())).toList());
            return new LlmAnalysisMetric.TopicResult(topic, EvaluationStatus.INSUFFICIENT_EVIDENCE, null, empty, attempts);
        }
        var schema = LlmResponseValidator.schema(roster, resources.rubric());
        String instructions = resources.systemPrompt() + "\n" + schema.json();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage(ChatMessage.Role.USER, Json.write(Map.of("topicIndex", topic, "participants", roster, "events", events))),
                new ChatMessage(ChatMessage.Role.USER, resources.cue())));
        for (int attempt = 0; attempt <= resources.config().maxRepairAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) return failure(topic, "evaluation_interrupted", attempts);
            var request = new ChatRequest(instructions, messages, model, ProviderCapabilities.structuredOutput(model) ? schema : null);
            if (Json.write(request).length() > resources.config().maxInputCharacters()) return failure(topic, "input_budget_exceeded", attempts);
            if (!budget.reserve()) return failure(topic, "generation_budget_exhausted", attempts);
            ChatResponse response;
            long start = System.nanoTime();
            try { response = Objects.requireNonNull(provider.complete(request)); }
            catch (RuntimeException e) {
                attempts.add(new LlmAnalysisMetric.Attempt(model.provider(), model.model(), null, null, (System.nanoTime() - start) / 1_000_000));
                return failure(topic, Thread.currentThread().isInterrupted() ? "evaluation_interrupted" : "provider_failed", attempts);
            }
            attempts.add(new LlmAnalysisMetric.Attempt(response.provider(), response.model(), response.status(), response.usage(), response.latencyMillis()));
            if (response.status() != ChatResponse.CompletionStatus.COMPLETED)
                return failure(topic, "response_" + response.status().name().toLowerCase(java.util.Locale.ROOT), attempts);
            String text = response.text();
            if (text == null || text.length() > resources.config().maxResponseCharacters()) return failure(topic, "response_budget_exceeded", attempts);
            try {
                var assessment = LlmResponseValidator.parse(text, topic, events, roster, resources.rubric());
                boolean scored = assessment.participants().stream().flatMap(p -> p.metrics().stream()).anyMatch(m -> m.status() == EvaluationStatus.OK);
                return new LlmAnalysisMetric.TopicResult(topic, scored ? EvaluationStatus.OK : EvaluationStatus.INSUFFICIENT_EVIDENCE,
                        null, assessment, attempts);
            } catch (IllegalArgumentException e) {
                if (attempt == resources.config().maxRepairAttempts()) return failure(topic, "invalid_assessment", attempts);
                messages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, text.isBlank() ? "{}" : text));
                messages.add(new ChatMessage(ChatMessage.Role.USER, resources.repairPrompt()));
            }
        }
        throw new IllegalStateException("Unreachable repair state");
    }
    private LlmAnalysisMetric.TopicResult failure(int topic, String error, List<LlmAnalysisMetric.Attempt> attempts) {
        return new LlmAnalysisMetric.TopicResult(topic, EvaluationStatus.FAILED, error, null, attempts);
    }
    private final class Budget {
        int calls;
        long reservedTokens;
        boolean reserve() {
            if (calls >= resources.config().maxCalls()
                    || reservedTokens + model.maxCompletionTokens() > resources.config().maxTotalCompletionTokens()) return false;
            calls++;
            reservedTokens += model.maxCompletionTokens();
            return true;
        }
    }
}
