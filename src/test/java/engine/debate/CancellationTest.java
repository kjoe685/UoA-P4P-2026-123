package engine.debate;

import engine.ChatManager;
import engine.agent.*;
import engine.io.EngineOutput;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CancellationTest {
    @Test void cancellationAfterResponseDoesNotPublishOrBroadcast() {
        AtomicInteger speeches = new AtomicInteger();
        DebateManager[] manager = new DebateManager[1];
        ChatManager provider = new ChatManager() {
            public void addMessage(String text) { }
            public void sendChat() { manager[0].requestStop(); }
            public String getMessageContent() { return "Abandoned speech"; }
        };
        Agent agent = new Agent("Labour MP", Party.LABOUR, AdversarialStrategy.NONE, provider, "Private setup");
        EngineOutput output = new EngineOutput() {
            public void topicStarted(int n, int total, String topic) { }
            public void displaySpeakerMessage(String text) { }
            public void speakerCalled(Agent speaker, boolean interjection) { }
            public void displayMessage(Agent speaker, String text, boolean interjection) { speeches.incrementAndGet(); }
        };
        manager[0] = new DebateManager(List.of(agent), List.of("Topic"), 1, output);
        manager[0].run();
        assertEquals(0, speeches.get());
    }
}
