package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.transcript.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationShutdownTest {
    @TempDir Path root;

    private static Transcript source() {
        var member=new Participant("labour","Labour MP",Party.LABOUR,"Labour");
        return new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(member),
                List.of(new Topic("topic-1","Housing",null)),
                List.of(new PublicEvent("turn-1","topic-1",PublicEvent.Type.SPEECH,member,"Retained public contribution")),Transcript.Outcome.COMPLETE);
    }

    @Test void closedApplicationRefusesStartBeforeConstructingProvidersOrSavingRuns() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs")); var providers=new AtomicInteger();
        var app=new DebateApplication(root,store,model -> { providers.incrementAndGet(); return request -> RunLifecycleTest.completed("Fake response"); });
        app.close();
        var error=assertThrows(IllegalStateException.class,() -> app.start(TestFixtures.settings()));
        assertTrue(error.getMessage().contains("closed")); assertEquals(0,providers.get());
        assertTrue(store.ids().isEmpty()); assertTrue(app.runs().isEmpty()); app.close();
    }

    @Test void closedApplicationRefusesImportAndKeepsEarlierPublicEvidenceExact() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs")); var source=source();
        var app=new DebateApplication(root,store,model -> { throw new AssertionError("Import must not construct providers"); });
        var saved=app.importTranscript(source); Path file=root.resolve("runs").resolve(saved.id()).resolve("transcript.json");
        byte[] retained=Files.readAllBytes(file); app.close();
        var error=assertThrows(IllegalStateException.class,() -> app.importTranscript(source));
        assertTrue(error.getMessage().contains("closed")); assertEquals(List.of(saved.id()),store.ids());
        assertArrayEquals(retained,Files.readAllBytes(file)); app.close();
        try (var recovered=new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not construct providers"); })) {
            assertEquals(saved.transcript(),recovered.find(saved.id()).transcript()); assertArrayEquals(retained,Files.readAllBytes(file));
        }
    }

    @Test void shutdownGateRefusesRequestsWhileAnAcceptedSittingFinishesPersistence() throws Exception {
        TestFixtures.copyResources(root); var closingEntered=new CountDownLatch(1); var releaseSave=new CountDownLatch(1);
        var providerEntered=new CountDownLatch(1); var providers=new AtomicInteger(); var pauseOnce=new AtomicBoolean(true);
        var store=new RunStore(root.resolve("runs")) {
            @Override public void saveTranscript(Transcript transcript) {
                if (transcript.outcome()==Transcript.Outcome.ADJOURNED && pauseOnce.getAndSet(false)) {
                    closingEntered.countDown();
                    try { if (!releaseSave.await(4,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture save not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture save interrupted"); }
                }
                super.saveTranscript(transcript);
            }
        };
        var app=new DebateApplication(root,store,model -> {
            providers.incrementAndGet(); return request -> {
                providerEntered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                throw new AssertionError("Fixture response must be cancelled");
            };
        });
        var worker=Executors.newSingleThreadExecutor();
        try {
            var sitting=app.start(TestFixtures.settings()); assertTrue(providerEntered.await(3,TimeUnit.SECONDS));
            int constructed=providers.get(); var closing=worker.submit(app::close);
            assertTrue(closingEntered.await(3,TimeUnit.SECONDS));
            var error=assertThrows(IllegalStateException.class,() -> app.start(TestFixtures.settings()));
            assertTrue(error.getMessage().contains("closed"));
            assertThrows(IllegalStateException.class,() -> app.importTranscript(source()));
            assertEquals(constructed,providers.get()); assertEquals(List.of(sitting.id()),store.ids());
            releaseSave.countDown(); closing.get(3,TimeUnit.SECONDS); RunLifecycleTest.finish(sitting);
            assertEquals(Transcript.Outcome.ADJOURNED,sitting.transcript().outcome());
            assertEquals(sitting.transcript(),store.transcript(sitting.id()));
            assertFalse(Json.write(sitting.transcript()).contains("Fixture"));
        } finally { releaseSave.countDown(); app.close(); worker.shutdownNow(); }
    }

    @Test void shutdownInterruptsSittingBeforeWaitingForBackgroundPersistence() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs")); var calls=new AtomicInteger();
        var providerEntered=new CountDownLatch(1); var providerInterrupted=new CountDownLatch(1); var releaseProvider=new CountDownLatch(1);
        var persistenceEntered=new CountDownLatch(1); var releasePersistence=new CountDownLatch(1);
        var app=new DebateApplication(root,store,model -> request -> {
            if (calls.incrementAndGet()==1) return RunLifecycleTest.completed("Earlier committed public evidence.");
            providerEntered.countDown();
            try { releaseProvider.await(); }
            catch (InterruptedException e) { providerInterrupted.countDown(); Thread.currentThread().interrupt(); }
            return RunLifecycleTest.completed("ABANDONED_PROVIDER_SENTINEL");
        });
        var workers=Executors.newFixedThreadPool(2); Future<?> closing=null;
        String id; byte[] retained;
        try {
            var sitting=app.start(TestFixtures.settings()); id=sitting.id(); assertTrue(providerEntered.await(3,TimeUnit.SECONDS));
            // Background transitions hold this same monitor while committing job progress.
            var persistence=workers.submit(() -> {
                synchronized (app.background()) {
                    persistenceEntered.countDown();
                    try { releasePersistence.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            });
            assertTrue(persistenceEntered.await(3,TimeUnit.SECONDS)); closing=workers.submit(app::close);
            assertTrue(providerInterrupted.await(3,TimeUnit.SECONDS),"Shutdown must interrupt generation before waiting for background persistence");
            assertFalse(closing.isDone(),"Shutdown must still wait for background cleanup");
            assertThrows(IllegalStateException.class,() -> app.start(TestFixtures.settings()));
            releasePersistence.countDown(); persistence.get(3,TimeUnit.SECONDS); closing.get(4,TimeUnit.SECONDS);
            RunLifecycleTest.finish(sitting); assertEquals(2,calls.get());
            assertEquals(Transcript.Outcome.ADJOURNED,sitting.transcript().outcome());
            assertEquals(sitting.transcript(),store.transcript(id));
            assertTrue(app.textExport(id).contains("Earlier committed public evidence."));
            assertFalse(Json.write(sitting.transcript()).contains("SENTINEL"));
            retained=Files.readAllBytes(root.resolve("runs").resolve(id).resolve("transcript.json"));
        } finally {
            releasePersistence.countDown(); releaseProvider.countDown();
            try { if (closing!=null) closing.get(4,TimeUnit.SECONDS); }
            finally { app.close(); workers.shutdownNow(); }
        }
        try (var recovered=new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not generate"); })) {
            assertEquals(Transcript.Outcome.ADJOURNED,recovered.find(id).transcript().outcome());
            assertArrayEquals(retained,Files.readAllBytes(root.resolve("runs").resolve(id).resolve("transcript.json")));
        }
    }
}
