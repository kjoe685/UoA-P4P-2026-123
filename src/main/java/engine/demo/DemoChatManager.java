package engine.demo;

import engine.ChatManager;
import engine.chat.*;

/** Fixed demonstration; deterministic and stateless, with no network or credentials. */
public final class DemoChatManager implements ChatManager {
    @Override public ChatResponse complete(ChatRequest request) {
        try { Thread.sleep(250); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Generation cancelled");
        }
        boolean first=request.messages().stream().noneMatch(message -> message.role()==ChatMessage.Role.ASSISTANT);
        String text=first
            ? "Mr Speaker, this demonstration opens the question before the House. We should consider the public benefit and the practical costs. I ask members to explain the evidence behind their proposals."
            : "Mr Speaker, I have heard the preceding contribution. The House should test that claim against its likely costs and benefits. I would support a reasoned amendment that addresses those concerns.";
        return new ChatResponse(text,"demo",request.model().model(),ChatResponse.CompletionStatus.COMPLETED,null,250);
    }
}
