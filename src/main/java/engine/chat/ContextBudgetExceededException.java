package engine.chat;

/** Safe local refusal before inference; never carries prompt content or provider error text. */
public final class ContextBudgetExceededException extends IllegalArgumentException {
    public static final String GUIDANCE = "Generation exceeds the local context budget. Increase the configured context limit if the model and hardware support it, or reduce grounding, rounds or output tokens, then retry.";

    public ContextBudgetExceededException() {
        super("Request exceeds the conservative Ollama context budget");
    }
}
