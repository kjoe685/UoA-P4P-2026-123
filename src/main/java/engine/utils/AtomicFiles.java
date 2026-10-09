package engine.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;

/** Commit complete contents before publishing an owner-side change. */
public final class AtomicFiles {
    private AtomicFiles() { }
    public static void write(Path path,String text) {
        byte[] bytes;
        try { bytes=Utf8.encode(text); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Cannot save malformed Unicode text"); }
        Path temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".part");
        String phase="directory creation";
        try {
            Files.createDirectories(path.getParent());
            phase="temporary file write";
            try (FileChannel channel=FileChannel.open(temporary,CREATE_NEW,WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            phase="file replacement";
            replace(temporary,path);
        } catch (IOException e) {
            // Static operational metadata only: never reveal the path, contents or raw filesystem error.
            String category=e instanceof AccessDeniedException ? "access denied or busy file"
                    : e instanceof java.nio.channels.ClosedByInterruptException ? "interrupted filesystem operation" : "filesystem error";
            String message="Cannot save during "+phase+" ("+category+"); check available disk space and write access";
            System.err.println(message);
            throw new IllegalStateException(message);
        }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    private static void replace(Path temporary,Path destination) throws IOException {
        int retries=0;
        while(true) {
            if(Thread.currentThread().isInterrupted()) throw new java.nio.channels.ClosedByInterruptException();
            try {
                try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException e) { Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch(AccessDeniedException e) {
                // Windows readers/scanners can briefly deny deletion of an existing file.
                // Retry this same flushed file only; never regenerate upstream evidence.
                if(!System.getProperty("os.name").startsWith("Windows") || retries==3 || !Files.isRegularFile(destination)) throw e;
                try { Thread.sleep(50L<<retries++); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.nio.channels.ClosedByInterruptException();
                }
            }
        }
    }
}
