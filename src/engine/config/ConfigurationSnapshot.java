package engine.config;

import engine.agent.HansardExcerpts;
import engine.prompt.PromptTemplate;
import engine.prompt.TemplateName;
import engine.utils.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, owner-side snapshot. Load once for each run, before constructing agents or calling providers. */
public final class ConfigurationSnapshot {
    private final EngineConfig config;
    private final Map<TemplateName, PromptTemplate> templates;
    private final HansardExcerpts excerpts;
    private final Map<String, String> sourceHashes;

    private ConfigurationSnapshot(EngineConfig config, Map<TemplateName, PromptTemplate> templates,
                                  HansardExcerpts excerpts, Map<String, String> sourceHashes) {
        this.config = config;
        this.templates = Map.copyOf(templates);
        this.excerpts = excerpts;
        this.sourceHashes = Map.copyOf(sourceHashes);
    }

    public static ConfigurationSnapshot load(Path resources) {
        Map<String, String> hashes = new LinkedHashMap<>();
        EngineConfig config = Json.read(read(resources, "config/engine.json", hashes), EngineConfig.class);
        Map<TemplateName, PromptTemplate> templates = new EnumMap<>(TemplateName.class);
        for (TemplateName name : TemplateName.values()) {
            String text = read(resources, "prompts/" + name.fileName(), hashes);
            templates.put(name, new PromptTemplate(name.fileName(), text, name.placeholders()));
        }
        HansardExcerpts excerpts = new HansardExcerpts(read(resources, "data/HansardExcerpts.json", hashes));
        return new ConfigurationSnapshot(config, templates, excerpts, hashes);
    }

    private static String read(Path root, String relative, Map<String, String> hashes) {
        try {
            byte[] bytes = Files.readAllBytes(root.resolve(relative));
            hashes.put(relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read configuration resource: " + relative);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public EngineConfig config() { return config; }
    public PromptTemplate template(TemplateName name) { return templates.get(name); }
    public HansardExcerpts excerpts() { return excerpts; }
    public Map<String, String> sourceHashes() { return sourceHashes; }

    @Override public String toString() { return "ConfigurationSnapshot[private run configuration]"; }
}
