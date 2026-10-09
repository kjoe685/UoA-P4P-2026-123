package engine;

import engine.utils.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import com.sun.nio.file.ExtendedOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path root;

    @Test void failedDirectoryCreationReportsItsPhaseWithoutPathsContentsOrCauses() throws Exception {
        Path blocked=root.resolve("PRIVATE_PATH_SENTINEL"); Files.writeString(blocked,"Original data.");
        var error=assertThrows(IllegalStateException.class,() -> AtomicFiles.write(blocked.resolve("new.json"),"PRIVATE_CONTENT_SENTINEL"));
        assertSafePhase(error,"directory creation");
        assertEquals("Original data.",Files.readString(blocked));
        try (var paths=Files.list(root)) { assertEquals(List.of(blocked),paths.toList()); }
    }

    @Test void failedReplacementPreservesDestinationAndRemovesUncommittedTemporaryFile() throws Exception {
        Path blocked=root.resolve("PRIVATE_PATH_SENTINEL"); Files.createDirectory(blocked);
        Path existing=blocked.resolve("existing.txt"); Files.writeString(existing,"Original data.");
        var error=assertThrows(IllegalStateException.class,() -> AtomicFiles.write(blocked,"PRIVATE_CONTENT_SENTINEL"));
        assertSafePhase(error,"file replacement");
        assertEquals("Original data.",Files.readString(existing));
        try (var paths=Files.list(root)) { assertEquals(List.of(blocked),paths.toList()); }
    }

    @Test void transientWindowsDeleteLockAllowsTheSameReplacementAfterRelease() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path destination=root.resolve("PRIVATE_PATH_SENTINEL.json"); Files.writeString(destination,"Previous bytes");
        var failure=new AtomicReference<Throwable>();
        Thread writer;
        try(var lock=FileChannel.open(destination,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)) {
            writer=new Thread(() -> {try {AtomicFiles.write(destination,"Committed bytes");} catch(Throwable e) {failure.set(e);}});
            writer.start(); Thread.sleep(100);
            assertEquals("Previous bytes",Files.readString(destination));
        }
        writer.join(3000);assertFalse(writer.isAlive());assertNull(failure.get());
        assertEquals("Committed bytes",Files.readString(destination));
        try(var files=Files.list(root)) {assertEquals(List.of(destination),files.toList());}
    }

    @Test void persistentWindowsDeleteLockPreservesBytesAndSanitizedFailure() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path destination=root.resolve("PRIVATE_PATH_SENTINEL.json");Files.writeString(destination,"Previous bytes");
        try(var lock=FileChannel.open(destination,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)) {
            var failure=assertThrows(IllegalStateException.class,() -> AtomicFiles.write(destination,"PRIVATE_CONTENT_SENTINEL"));
            assertSafePhase(failure,"file replacement");assertEquals("Previous bytes",Files.readString(destination));
        }
        try(var files=Files.list(root)) {assertEquals(List.of(destination),files.toList());}
    }

    @Test void interruptedWindowsReplacementDoesNotPublishOrLeaveTemporaryBytes() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path destination=root.resolve("PRIVATE_PATH_SENTINEL.json");Files.writeString(destination,"Previous bytes");
        var failure=new AtomicReference<Throwable>();
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer;
        try(var lock=FileChannel.open(destination,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)) {
            writer=new Thread(() -> {
                try {AtomicFiles.write(destination,"PRIVATE_CONTENT_SENTINEL");} catch(Throwable e) {failure.set(e);}
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            writer.start();Thread.sleep(75);writer.interrupt();writer.join(3000);
            assertFalse(writer.isAlive());assertTrue(interrupted.get());
            assertInstanceOf(IllegalStateException.class,failure.get());
            assertSafePhase((IllegalStateException)failure.get(),"file replacement");
            assertEquals("Previous bytes",Files.readString(destination));
        }
        try(var files=Files.list(root)) {assertEquals(List.of(destination),files.toList());}
    }

    private static void assertSafePhase(IllegalStateException error,String phase) {
        assertTrue(error.getMessage().contains(phase)); assertTrue(error.getMessage().contains("write access"));
        assertFalse(error.getMessage().contains("SENTINEL")); assertNull(error.getCause());
    }
}
