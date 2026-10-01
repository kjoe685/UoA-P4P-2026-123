package engine.debate;

import engine.agent.*;
import engine.chat.ChatResponse;
import engine.config.ConfigurationSnapshot;
import engine.io.EngineOutput;
import engine.prompt.PromptManager;
import engine.transcript.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CancellationTest {
    @Test void cancellationAfterResponseDoesNotPublishOrBroadcast() {
        var snapshot=ConfigurationSnapshot.load(Path.of("."));
        AtomicInteger speeches=new AtomicInteger();
        DebateManager[] manager=new DebateManager[1];
        var provider=(engine.ChatManager) request -> {
            manager[0].requestStop();
            return new ChatResponse("Abandoned speech","demo","demo",ChatResponse.CompletionStatus.COMPLETED,null,0);
        };
        var agent=new Agent(new Participant("labour","Labour MP",Party.LABOUR,"Labour"),AdversarialStrategy.NONE,
                provider,"Private setup",List.of(),snapshot.config().agentModel());
        EngineOutput output=event -> { if (event.speaker()!=null) speeches.incrementAndGet(); };
        manager[0]=new DebateManager(List.of(agent),List.of(new Topic("topic-1","Topic",null)),1,output,
                new PromptManager(snapshot),snapshot.config().interruptions());
        manager[0].run();
        assertEquals(0,speeches.get());
    }
}
