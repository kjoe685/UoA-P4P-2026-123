package engine;

import engine.utils.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
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

    private static void assertSafePhase(IllegalStateException error,String phase) {
        assertTrue(error.getMessage().contains(phase)); assertTrue(error.getMessage().contains("write access"));
        assertFalse(error.getMessage().contains("SENTINEL")); assertNull(error.getCause());
    }
}
