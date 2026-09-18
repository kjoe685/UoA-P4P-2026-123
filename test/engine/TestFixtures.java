package engine;

import engine.agent.AdversarialStrategy;
import engine.agent.Agent;
import engine.agent.Party;
import engine.chat.ChatRequest;
import engine.chat.ChatResponse;
import engine.config.ConfigurationSnapshot;
import engine.config.ModelConfig;
import engine.prompt.PromptManager;
import engine.transcript.Participant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestFixtures {
    private TestFixtures() { }

    public static ConfigurationSnapshot snapshot() { return ConfigurationSnapshot.load(Path.of("resources")); }
    public static ModelConfig model() { return snapshot().config().agentModel(); }

    public static Agent agent(Party party, AdversarialStrategy strategy, ChatManager provider) {
        var snapshot = snapshot();
        var profile = snapshot.config().parties().get(party);
        var excerpts = snapshot.excerpts().getExcerpts(party);
        String name = profile.displayName() + " MP";
        String prompt = new PromptManager(snapshot).assemblePersonaPrompt(name, profile, strategy, excerpts);
        return new Agent(new Participant(party.name(), name, profile.displayName()), strategy,
                provider, prompt, excerpts, snapshot.config().agentModel());
    }

    public static ChatResponse response(String text) {
        return new ChatResponse(text, "mock", "mock-model", ChatResponse.CompletionStatus.COMPLETED, null, 0);
    }

    public static final class RecordingProvider implements ChatManager {
        public final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse complete(ChatRequest request) {
            requests.add(request);
            return response("Public speech " + requests.size());
        }
    }
}
