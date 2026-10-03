package engine.debate;

import engine.agent.*;
import engine.chat.ChatResponse;
import engine.config.ConfigurationSnapshot;
import engine.config.InterruptionConfig;
import engine.io.EngineOutput;
import engine.prompt.PromptManager;
import engine.transcript.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CancellationTest {
    @Test void stopIntentDuringCallingPreventsOrdinaryProviderInvocation() {
        var snapshot=ConfigurationSnapshot.load(Path.of(".")); var calls=new AtomicInteger(); var speeches=new AtomicInteger();
        DebateManager[] manager=new DebateManager[1];
        var provider=(engine.ChatManager) request -> { calls.incrementAndGet(); return new ChatResponse("Public speech","demo","demo",ChatResponse.CompletionStatus.COMPLETED,null,0); };
        var agent=new Agent(new Participant("labour","Labour MP",Party.LABOUR,"Labour"),AdversarialStrategy.NONE,
                provider,"Private setup",List.of(),snapshot.config().agentModel());
        EngineOutput output=new EngineOutput() {
            public void publicEvent(PublicEvent event) { if (event.speaker()!=null) speeches.incrementAndGet(); }
            public void speakerCalled(Participant speaker,boolean interjection) { manager[0].signalStop(); }
        };
        manager[0]=new DebateManager(List.of(agent),List.of(new Topic("topic-1","Topic",null)),1,output,
                new PromptManager(snapshot),new InterruptionConfig(0,0,1));
        manager[0].run(); assertEquals(0,calls.get(),"Stopping during calling must avoid an unstarted provider request");
        assertEquals(0,speeches.get());
    }

    @Test void stopIntentDuringInterjectionCallingKeepsEarlierSpeechWithoutAnotherProviderInvocation() {
        var snapshot=ConfigurationSnapshot.load(Path.of(".")); var calls=new AtomicInteger(); var speeches=new AtomicInteger();
        DebateManager[] manager=new DebateManager[1];
        var provider=(engine.ChatManager) request -> { calls.incrementAndGet(); return new ChatResponse("Public speech","demo","demo",ChatResponse.CompletionStatus.COMPLETED,null,0); };
        var labour=new Agent(new Participant("labour","Labour MP",Party.LABOUR,"Labour"),AdversarialStrategy.NONE,
                provider,"Private setup",List.of(),snapshot.config().agentModel());
        var national=new Agent(new Participant("national","National MP",Party.NATIONAL,"National"),AdversarialStrategy.NONE,
                provider,"Private setup",List.of(),snapshot.config().agentModel());
        EngineOutput output=new EngineOutput() {
            public void publicEvent(PublicEvent event) { if (event.speaker()!=null) speeches.incrementAndGet(); }
            public void speakerCalled(Participant speaker,boolean interjection) { if (interjection) manager[0].signalStop(); }
        };
        manager[0]=new DebateManager(List.of(labour,national),List.of(new Topic("topic-1","Topic",null)),1,output,
                new PromptManager(snapshot),new InterruptionConfig(1,1,1));
        manager[0].run(); assertEquals(1,calls.get(),"Stopping while calling an interjection must avoid its provider request");
        assertEquals(1,speeches.get());
    }

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
