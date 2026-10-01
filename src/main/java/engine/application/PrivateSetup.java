package engine.application;

import engine.config.EngineConfig;
import java.util.Map;

/** Owner-only record. It is never passed to agents, evaluators or public export. */
public record PrivateSetup(RunSpec settings, EngineConfig configuration, Map<String,String> sourceContents,
                           Map<String,String> sourceHashes, Map<String,String> resolvedPrompts) {
    public PrivateSetup {
        sourceContents=Map.copyOf(sourceContents); sourceHashes=Map.copyOf(sourceHashes); resolvedPrompts=Map.copyOf(resolvedPrompts);
    }
    @Override public String toString() { return "PrivateSetup[redacted]"; }
}
