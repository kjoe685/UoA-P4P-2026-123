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
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
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
}
