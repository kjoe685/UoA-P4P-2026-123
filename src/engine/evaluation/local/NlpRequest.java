package engine.evaluation.local;

import java.util.List;

/** Allowlisted classifier input: no speaker identity, party, prompts, or assignment metadata. */
public record NlpRequest(int schemaVersion, List<String> methods, List<Turn> turns) {
    public NlpRequest { methods = List.copyOf(methods); turns = List.copyOf(turns); }
    public record Target(String id, String proposition) { }
    public record Turn(long turnId, String text, List<Target> targets) {
        public Turn { targets = List.copyOf(targets); }
    }
}
