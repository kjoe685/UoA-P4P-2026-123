package engine.application;

import engine.transcript.Transcript;
import engine.utils.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** File-backed owner storage. Public transcripts are committed before UI notification. */
public class RunStore {
    private final Path root;
    public RunStore(Path root) { this.root=root.toAbsolutePath().normalize(); }
    private Path directory(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid run id");
        return root.resolve(id);
    }
    public void create(String id,PrivateSetup setup) { write(directory(id).resolve("setup.json"),setup); }
    public void saveTranscript(Transcript transcript) { write(directory(transcript.runId()).resolve("transcript.json"),transcript); }
    public void saveView(String id,List<String> events) { write(directory(id).resolve("view.json"),events); }
    public Transcript transcript(String id) { return Json.read(read(directory(id).resolve("transcript.json")),Transcript.class); }
    public PrivateSetup setup(String id) { return Json.read(read(directory(id).resolve("setup.json")),PrivateSetup.class); }
    public List<String> view(String id) {
        Object raw=Json.parse(read(directory(id).resolve("view.json")));
        if (!(raw instanceof List<?> entries) || entries.stream().anyMatch(item -> !(item instanceof String)))
            throw new IllegalArgumentException("Invalid saved view");
        return entries.stream().map(String.class::cast).toList();
    }
    public List<String> ids() {
        if (!Files.isDirectory(root)) return List.of();
        try (var directories=Files.list(root)) {
            return directories.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .filter(id -> id.matches("[0-9a-f-]{36}")).sorted().toList();
        } catch (IOException e) { throw new IllegalStateException("Cannot list saved runs"); }
    }
    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { throw new IllegalStateException("Cannot read saved run"); }
    }
    private static void write(Path path,Object value) {
        Path temporary=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes=Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel channel=FileChannel.open(temporary,CREATE_NEW,WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save run; check available disk space and write access"); }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
