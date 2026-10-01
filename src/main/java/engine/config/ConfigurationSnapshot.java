package engine.config;

import engine.agent.HansardExcerpts;
import engine.prompt.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import engine.utils.Json;

/** Immutable owner-side resources. Loaded once before any model calls. */
public final class ConfigurationSnapshot {
    private final EngineConfig config;
    private final Map<TemplateName, PromptTemplate> templates;
    private final HansardExcerpts excerpts;
    private final Map<String, String> contents;
    private final Map<String, String> hashes;

    private ConfigurationSnapshot(EngineConfig config, Map<TemplateName, PromptTemplate> templates,
                                  HansardExcerpts excerpts, Map<String,String> contents, Map<String,String> hashes) {
        this.config=config; this.templates=Map.copyOf(templates); this.excerpts=excerpts;
        this.contents=Map.copyOf(contents); this.hashes=Map.copyOf(hashes);
    }

    public static ConfigurationSnapshot load(Path root) {
        Map<String,String> contents=new LinkedHashMap<>(), hashes=new LinkedHashMap<>();
        EngineConfig config=Json.read(read(root, "config/engine.json", contents, hashes), EngineConfig.class);
        Map<TemplateName,PromptTemplate> templates=new EnumMap<>(TemplateName.class);
        for (TemplateName name:TemplateName.values())
            templates.put(name,new PromptTemplate(name.fileName(),
                    read(root,"prompts/"+name.fileName(),contents,hashes),name.placeholders()));
        HansardExcerpts excerpts=new HansardExcerpts(read(root,"data/hansard/excerpts.json",contents,hashes));
        return new ConfigurationSnapshot(config,templates,excerpts,contents,hashes);
    }

    private static String read(Path root,String relative,Map<String,String> contents,Map<String,String> hashes) {
        try {
            byte[] data=Files.readAllBytes(root.resolve(relative));
            String text=new String(data,StandardCharsets.UTF_8);
            contents.put(relative,text);
            hashes.put(relative,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
            return text;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read resource: "+relative); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public EngineConfig config() { return config; }
    public PromptTemplate template(TemplateName name) { return templates.get(name); }
    public HansardExcerpts excerpts() { return excerpts; }
    public Map<String,String> sourceContents() { return contents; }
    public Map<String,String> sourceHashes() { return hashes; }
    @Override public String toString() { return "ConfigurationSnapshot[private resources redacted]"; }
}
