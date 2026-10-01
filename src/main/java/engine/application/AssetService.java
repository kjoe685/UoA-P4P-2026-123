package engine.application;

import engine.config.ConfigurationSnapshot;
import engine.prompt.TemplateName;
import engine.utils.AtomicFiles;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Explicit owner-editable resources; no credentials or arbitrary filesystem paths. */
public final class AssetService {
    private final Path root;
    private final List<String> paths;
    public AssetService(Path root) {
        this.root=root.toAbsolutePath().normalize();
        List<String> names=new ArrayList<>(List.of("config/engine.json","config/local-evaluation.json","data/hansard/excerpts.json"));
        for (TemplateName name:TemplateName.values()) names.add("prompts/"+name.fileName());
        paths=List.copyOf(names);
    }
    public List<String> paths() { return paths; }
    private Path path(String name) {
        if (!paths.contains(name)) throw new IllegalArgumentException("Choose a listed editable asset");
        Path path=root;
        for (Path component:Path.of(name)) {
            path=path.resolve(component);
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Editable assets cannot use symbolic links");
        }
        return path;
    }
    public String read(String name) {
        try { return Files.readString(path(name)); }
        catch (IOException e) { throw new IllegalStateException("Cannot read editable asset"); }
    }
    public synchronized void update(String name,String text,boolean save) {
        Path path=path(name);
        if (text==null || text.isBlank() || text.length()>1_000_000) throw new IllegalArgumentException("Asset needs 1–1000000 characters");
        if (name.equals("config/local-evaluation.json")) engine.utils.Json.read(text,engine.evaluation.local.LocalEvaluationConfig.class);
        else ConfigurationSnapshot.load(root,Map.of(name,text));
        if (save) AtomicFiles.write(path,text);
    }
}
