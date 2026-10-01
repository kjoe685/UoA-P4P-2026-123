package engine.agent;

import engine.config.ModelConfig;
import java.util.List;
import java.util.Objects;

/** Package-private; never a transcript/export DTO. Future position cards and concessions belong here. */
final class PrivateAgentContext {
    final String systemPrompt;
    final AdversarialStrategy strategy;
    final List<String> groundingExcerpts;
    final ModelConfig model;

    PrivateAgentContext(String systemPrompt, AdversarialStrategy strategy,
                        List<String> groundingExcerpts, ModelConfig model) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt);
        this.strategy = Objects.requireNonNull(strategy);
        this.groundingExcerpts = List.copyOf(groundingExcerpts);
        this.model = Objects.requireNonNull(model);
    }

    @Override public String toString() { return "PrivateAgentContext[redacted]"; }
}
