package engine.application;

import engine.config.EngineConfig;
import engine.utils.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Named, non-secret settings resolved by the same contract used to start a run. */
public final class SettingsService {
    private final Path root;
    public SettingsService(Path root) { this.root=root; }
    private Path path(String name) {
        if (name==null || !name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Settings name needs 1–64 letters, digits, underscores or hyphens");
        return root.resolve(name+".json");
    }
    public List<String> names() {
        if (!Files.isDirectory(root)) return List.of();
        try (var files=Files.list(root)) {
            return files.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("[A-Za-z0-9_-]{1,64}\\.json"))
                    .map(name -> name.substring(0,name.length()-5)).sorted().toList();
        } catch (IOException e) { throw new IllegalStateException("Cannot list saved settings"); }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> read(String name) {
        Path file=path(name);
        if (!Files.exists(file)) throw new NoSuchElementException("No saved settings with that name");
        try {
            Object parsed=Json.parse(Files.readString(file));
            if (!(parsed instanceof Map<?,?>)) throw new IllegalArgumentException("Invalid saved settings");
            return (Map<String,Object>)parsed;
        } catch (IOException e) { throw new IllegalStateException("Cannot read saved settings"); }
    }
    public Map<String,Object> resolve(Map<String,Object> explicit) {
        Map<String,Object> merged=new LinkedHashMap<>();
        if (explicit.containsKey("settingsName")) {
            if (!(explicit.get("settingsName") instanceof String name)) throw new IllegalArgumentException("settingsName must be text");
            merged.putAll(read(name));
        }
        explicit.forEach((key,value) -> { if (!key.equals("settingsName")) merged.put(key,value); });
        return merged;
    }
    public synchronized Map<String,Object> save(String name,Map<String,Object> input,EngineConfig config) {
        Path file=path(name);
        var canonical=RunSpec.resolve(resolve(input),config).settings();
        AtomicFiles.write(file,Json.write(canonical));
        return canonical;
    }
}
