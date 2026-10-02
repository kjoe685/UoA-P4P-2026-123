package engine.application;

import engine.TestFixtures;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JobShutdownTest {
    @TempDir Path root;

    /** Keep the committed bytes intact while deterministically refusing replacement on any OS. */
    private static final class BlockedSave implements AutoCloseable {
        final Path file, retained, blocker;
        final byte[] committed;
        BlockedSave(Path file) throws Exception {
            this.file=file; retained=file.resolveSibling("retained-job.json");
            committed=Files.readAllBytes(file); Files.move(file,retained);
            Files.createDirectory(file); blocker=file.resolve("PRIVATE_FILESYSTEM_SENTINEL");
            Files.writeString(blocker,"PRIVATE_FILESYSTEM_SENTINEL");
        }
        @Override public void close() throws Exception {
            assertArrayEquals(committed,Files.readAllBytes(retained));
            Files.delete(blocker); Files.delete(file); Files.move(retained,file);
        }
    }

    private static final class WaitingWork implements JobService.Work {
        final CountDownLatch ready=new CountDownLatch(1), release=new CountDownLatch(1), interrupted=new CountDownLatch(1);
        final AtomicInteger subsequentWork=new AtomicInteger();
        @Override public boolean run(JobService.Context context) throws Exception {
            context.update("Committed partial result",Map.of("retained","PUBLIC_PARTIAL_RESULT")); ready.countDown();
            try { release.await(); }
            catch (InterruptedException e) { interrupted.countDown(); }
            // InterruptedException clears the interrupt flag; cancellation intent must still be checked.
            context.checkCancelled(); subsequentWork.incrementAndGet(); return true;
        }
    }

    private static void terminal(JobService jobs,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while (!jobs.find(id).terminal() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(jobs.find(id).terminal(),"Worker must finish after cancellation");
    }

    @Test void refusedCancellationStillInterruptsWorkAndRecoversCommittedPartialResultWithoutResuming() throws Exception {
        Path storage=root.resolve("jobs"); var jobs=new JobService(storage); var work=new WaitingWork();
        String id=jobs.submit("fixture",null,Map.of(),work).id();
        assertTrue(work.ready.await(3,TimeUnit.SECONDS));
        try (var blocked=new BlockedSave(storage.resolve(id).resolve("job.json"))) {
            var error=assertThrows(IllegalStateException.class,() -> jobs.cancel(id));
            assertFalse(error.getMessage().contains("SENTINEL")); assertNull(error.getCause());
            assertTrue(work.interrupted.await(1,TimeUnit.SECONDS),"Refused cancellation must still interrupt the worker");
            terminal(jobs,id); assertEquals(0,work.subsequentWork.get());
            assertArrayEquals(blocked.committed,Files.readAllBytes(blocked.retained));
        } finally { work.release.countDown(); terminal(jobs,id); jobs.close(); }
        try (var recovered=new JobService(storage)) {
            var job=recovered.find(id); assertEquals(BackgroundJob.State.INTERRUPTED,job.state());
            assertEquals(Map.of("retained","PUBLIC_PARTIAL_RESULT"),job.result());
            assertEquals(0,work.subsequentWork.get());
        }
    }

    @Test void refusedShutdownCancelsAllJobsAndNeverRunsQueuedWork() throws Exception {
        Path storage=root.resolve("jobs"); var jobs=new JobService(storage); var work=new WaitingWork();
        String active=jobs.submit("fixture-active",null,Map.of(),work).id();
        assertTrue(work.ready.await(3,TimeUnit.SECONDS)); var queuedCalls=new AtomicInteger();
        String queued=jobs.submit("fixture-queued",null,Map.of(),context -> { queuedCalls.incrementAndGet(); return true; }).id();
        try (var blocked=new BlockedSave(storage.resolve(active).resolve("job.json"))) {
            var error=assertThrows(IllegalStateException.class,jobs::close);
            assertFalse(error.getMessage().contains("SENTINEL"));
            assertTrue(work.interrupted.await(1,TimeUnit.SECONDS),"Shutdown must interrupt work even if a save fails");
            terminal(jobs,active); terminal(jobs,queued);
            assertEquals(0,work.subsequentWork.get()); assertEquals(0,queuedCalls.get());
            assertArrayEquals(blocked.committed,Files.readAllBytes(blocked.retained));
        } finally { work.release.countDown(); terminal(jobs,active); jobs.close(); }
        try (var recovered=new JobService(storage)) {
            assertEquals(BackgroundJob.State.INTERRUPTED,recovered.find(active).state());
            assertEquals(Map.of("retained","PUBLIC_PARTIAL_RESULT"),recovered.find(active).result());
            assertEquals(BackgroundJob.State.CANCELLED,recovered.find(queued).state());
        }
    }

    @Test void postShutdownSubmissionsAreRefusedBeforeCreatingJobFiles() throws Exception {
        Path storage=root.resolve("jobs"); var jobs=new JobService(storage); jobs.close();
        var calls=new AtomicInteger();
        var error=assertThrows(IllegalStateException.class,() -> jobs.submit("fixture",null,Map.of(),context -> { calls.incrementAndGet(); return true; }));
        assertTrue(error.getMessage().contains("closed")); assertEquals(0,calls.get()); assertFalse(Files.exists(storage));
    }

    @Test void refusedQueuedCancellationRemovesWorkBeforeItsWorkerSlotOpens() throws Exception {
        Path storage=root.resolve("jobs"); var jobs=new JobService(storage); var work=new WaitingWork();
        String active=jobs.submit("fixture-active",null,Map.of(),work).id(); assertTrue(work.ready.await(3,TimeUnit.SECONDS));
        var queuedEntered=new CountDownLatch(1);
        String queued=jobs.submit("fixture-queued",null,Map.of(),context -> { queuedEntered.countDown(); return true; }).id();
        try (var blocked=new BlockedSave(storage.resolve(queued).resolve("job.json"))) {
            assertThrows(IllegalStateException.class,() -> jobs.cancel(queued));
            work.release.countDown(); terminal(jobs,active);
            assertFalse(queuedEntered.await(1,TimeUnit.SECONDS),"A cancelled queued job must never enter its work");
            assertArrayEquals(blocked.committed,Files.readAllBytes(blocked.retained));
        } finally { work.release.countDown(); terminal(jobs,active); jobs.close(); }
        try (var recovered=new JobService(storage)) { assertEquals(BackgroundJob.State.CANCELLED,recovered.find(queued).state()); }
    }

    @Test void applicationShutdownAlwaysStopsOwnedRuntimeAndSittingAfterJobSaveRefusal() throws Exception {
        TestFixtures.copyResources(root); var providerEntered=new CountDownLatch(1); var releaseProvider=new CountDownLatch(1);
        var work=new WaitingWork();
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            var app=new DebateApplication(root,new RunStore(root.resolve("runs")),(model,runtime) -> request -> {
                providerEntered.countDown();
                try { releaseProvider.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return RunLifecycleTest.completed("Late response must be discarded");
            },null,manager);
            try {
                var setup=app.background().submit("fixture-setup",null,Map.of(),context -> { manager.install(fixture.config(),context); manager.ensureRunning(fixture.config()); return true; });
                assertEquals(BackgroundJob.State.COMPLETE,ManagedOllamaTest.finish(app.background(),setup.id()).state());
                var sitting=app.start(TestFixtures.settings()); assertTrue(providerEntered.await(3,TimeUnit.SECONDS));
                String id=app.background().submit("fixture-active",null,Map.of(),work).id(); assertTrue(work.ready.await(3,TimeUnit.SECONDS));
                try (var blocked=new BlockedSave(root.resolve("runs/jobs").resolve(id).resolve("job.json"))) {
                    assertThrows(IllegalStateException.class,app::close);
                    assertEquals(false,manager.readiness(fixture.config()).get("running"),"Owned runtime must stop despite refused job persistence");
                    RunLifecycleTest.finish(sitting);
                    assertEquals(engine.transcript.Transcript.Outcome.ADJOURNED,sitting.transcript().outcome());
                    assertFalse(Json.write(sitting.transcript()).contains("Late response"));
                    terminal(app.background(),id); assertEquals(0,work.subsequentWork.get());
                } finally { work.release.countDown(); terminal(app.background(),id); }
            } finally { releaseProvider.countDown(); work.release.countDown(); app.close(); }
        }
    }
}
