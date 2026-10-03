package engine.application;

import engine.TestFixtures;
import engine.transcript.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentShutdownTest {
    @TempDir Path root;
    private record PausedSave(long threadId,CountDownLatch release) { }

    @Test void blockedPublicSaveCannotLeaveBackgroundWorkRunningDuringShutdown() throws Exception {
        TestFixtures.copyResources(root); var saveEntered=new CountDownLatch(1); var releaseSave=new CountDownLatch(1);
        var workEntered=new CountDownLatch(1); var releaseWork=new CountDownLatch(1); var extraWork=new AtomicInteger();
        var saveThread=new AtomicLong(); var pauseOnce=new AtomicBoolean(true);
        var store=new RunStore(root.resolve("runs")) {
            @Override public void saveTranscript(Transcript transcript) {
                if (transcript.outcome()==Transcript.Outcome.RUNNING && transcript.events().stream().anyMatch(event -> event.type()==PublicEvent.Type.SPEECH)
                        && pauseOnce.getAndSet(false)) {
                    saveThread.set(Thread.currentThread().getId()); saveEntered.countDown();
                    try { releaseSave.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Accepted save was interrupted"); }
                }
                super.saveTranscript(transcript);
            }
        };
        var app=new DebateApplication(root,store,model -> request -> RunLifecycleTest.completed("Committed public speech."));
        var worker=Executors.newSingleThreadExecutor(); var closer=new AtomicReference<Thread>(); Future<?> closing=null;
        String jobId; byte[] retained;
        try {
            var sitting=app.start(TestFixtures.settings()); assertTrue(saveEntered.await(4,TimeUnit.SECONDS));
            jobId=app.background().submit("fixture",null,Map.of(),context -> {
                context.update("Committed partial result",Map.of("retained","PUBLIC_PARTIAL_RESULT")); workEntered.countDown();
                releaseWork.await(); context.checkCancelled(); extraWork.incrementAndGet(); return true;
            }).id();
            assertTrue(workEntered.await(3,TimeUnit.SECONDS));
            closing=worker.submit(() -> { closer.set(Thread.currentThread()); app.close(); });
            boolean blocked=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            var threads=ManagementFactory.getThreadMXBean();
            while (!blocked && System.nanoTime()<deadline) {
                var thread=closer.get(); var info=thread==null ? null : threads.getThreadInfo(thread.getId());
                blocked=info!=null && info.getThreadState()==Thread.State.BLOCKED && info.getLockOwnerId()==saveThread.get();
                if (!blocked) Thread.sleep(10);
            }
            assertTrue(blocked,"Shutdown must preserve the accepted public save");
            releaseWork.countDown(); var job=LocalEvaluationTest.finish(app.background(),jobId);
            assertEquals(0,extraWork.get(),"Background work must observe shutdown before its next step while a sitting save is blocked");
            assertEquals(BackgroundJob.State.CANCELLED,job.state()); assertEquals(Map.of("retained","PUBLIC_PARTIAL_RESULT"),job.result());
            assertThrows(IllegalStateException.class,() -> app.background().submit("after-close",null,Map.of(),context -> true));
            retained=Files.readAllBytes(root.resolve("runs/jobs").resolve(jobId).resolve("job.json"));
            releaseSave.countDown(); closing.get(4,TimeUnit.SECONDS); RunLifecycleTest.finish(sitting);
            assertEquals(Transcript.Outcome.ADJOURNED,sitting.transcript().outcome());
        } finally {
            releaseWork.countDown(); releaseSave.countDown();
            try { if (closing!=null) closing.get(4,TimeUnit.SECONDS); }
            finally { app.close(); worker.shutdownNow(); }
        }
        try (var recovered=new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not generate"); })) {
            assertEquals(BackgroundJob.State.CANCELLED,recovered.background().find(jobId).state());
            assertEquals(Map.of("retained","PUBLIC_PARTIAL_RESULT"),recovered.background().find(jobId).result());
            assertArrayEquals(retained,Files.readAllBytes(root.resolve("runs/jobs").resolve(jobId).resolve("job.json")));
            assertEquals(0,extraWork.get());
        }
    }

    @Test void oneBlockedPublicSaveCannotLeaveAnotherSittingGeneratingDuringShutdown() throws Exception {
        TestFixtures.copyResources(root); var entered=new CountDownLatch(2);
        var saves=new ConcurrentHashMap<String,PausedSave>(); var calls=new AtomicInteger();
        var store=new RunStore(root.resolve("runs")) {
            @Override public void saveTranscript(Transcript transcript) {
                if (transcript.outcome()==Transcript.Outcome.RUNNING && transcript.events().stream().anyMatch(event -> event.type()==PublicEvent.Type.SPEECH)) {
                    var paused=new PausedSave(Thread.currentThread().getId(),new CountDownLatch(1));
                    if (saves.putIfAbsent(transcript.runId(),paused)==null) {
                        entered.countDown();
                        try { paused.release().await(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Accepted save was interrupted"); }
                    }
                }
                super.saveTranscript(transcript);
            }
        };
        var app=new DebateApplication(root,store,model -> request -> {
            calls.incrementAndGet(); return RunLifecycleTest.completed("Committed public speech.");
        });
        var worker=Executors.newSingleThreadExecutor(); var closer=new AtomicReference<Thread>(); Future<?> closing=null;
        var retained=new HashMap<String,byte[]>();
        try {
            var first=app.start(TestFixtures.settings()); var second=app.start(TestFixtures.settings());
            assertTrue(entered.await(4,TimeUnit.SECONDS)); assertEquals(2,calls.get());
            closing=worker.submit(() -> { closer.set(Thread.currentThread()); app.close(); });
            // Observe which genuine persistence monitor shutdown waits for; UUID/map order is irrelevant.
            String blockedId=null; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            var threads=ManagementFactory.getThreadMXBean();
            while (blockedId==null && System.nanoTime()<deadline) {
                var thread=closer.get(); var info=thread==null ? null : threads.getThreadInfo(thread.getId());
                if (info!=null && info.getThreadState()==Thread.State.BLOCKED) {
                    for (var entry:saves.entrySet()) if (entry.getValue().threadId()==info.getLockOwnerId()) blockedId=entry.getKey();
                }
                if (blockedId==null) Thread.sleep(10);
            }
            assertNotNull(blockedId,"Shutdown must wait for the accepted save, rather than interrupting it");
            assertThrows(IllegalStateException.class,() -> app.start(TestFixtures.settings()));
            var released=first.id().equals(blockedId) ? second : first;
            saves.get(released.id()).release().countDown(); RunLifecycleTest.finish(released);
            assertEquals(2,calls.get(),"A sitting must not generate again while shutdown waits for another sitting's save");
            assertEquals(Transcript.Outcome.ADJOURNED,released.transcript().outcome());
            assertFalse(closing.isDone(),"The other accepted public save must still be allowed to finish");
            saves.get(blockedId).release().countDown(); closing.get(4,TimeUnit.SECONDS);
            for (var sitting:List.of(first,second)) {
                RunLifecycleTest.finish(sitting); assertEquals(Transcript.Outcome.ADJOURNED,sitting.transcript().outcome());
                assertEquals(sitting.transcript(),store.transcript(sitting.id()));
                assertEquals(1,sitting.transcript().events().stream().filter(event -> event.type()==PublicEvent.Type.SPEECH).count());
                retained.put(sitting.id(),Files.readAllBytes(root.resolve("runs").resolve(sitting.id()).resolve("transcript.json")));
            }
        } finally {
            saves.values().forEach(paused -> paused.release().countDown());
            try { if (closing!=null) closing.get(4,TimeUnit.SECONDS); }
            finally { app.close(); worker.shutdownNow(); }
        }
        try (var recovered=new DebateApplication(root,store,model -> { throw new AssertionError("Recovery must not generate"); })) {
            for (var entry:retained.entrySet()) {
                assertEquals(Transcript.Outcome.ADJOURNED,recovered.find(entry.getKey()).transcript().outcome());
                assertArrayEquals(entry.getValue(),Files.readAllBytes(root.resolve("runs").resolve(entry.getKey()).resolve("transcript.json")));
            }
        }
    }
}
