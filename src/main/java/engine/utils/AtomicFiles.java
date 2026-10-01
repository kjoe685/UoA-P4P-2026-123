package engine.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;

/** Commit complete contents before publishing an owner-side change. */
public final class AtomicFiles {
    private AtomicFiles() { }
    public static void write(Path path,String text) {
        Path temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel=FileChannel.open(temporary,CREATE_NEW,WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save; check available disk space and write access"); }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
