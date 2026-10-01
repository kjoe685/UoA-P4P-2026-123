package engine.agent;

import engine.utils.Json;
import java.util.*;

/** The existing small corpus, frozen at run start. Speaker metadata never enters a prompt. */
public final class HansardExcerpts {
    private final Map<Party, List<String>> excerptsByParty;
    private final Map<Party,List<Map<String,Object>>> recordsByParty;

    public HansardExcerpts(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Hansard must be an object");
        Map<Party, List<String>> result = new EnumMap<>(Party.class);
        Map<Party,List<Map<String,Object>>> records=new EnumMap<>(Party.class);
        for (Party party : Party.values()) {
            Object raw = root.get(party.getDisplayName());
            if (!(raw instanceof List<?> entries)) {
                throw new IllegalArgumentException("Missing Hansard sample for " + party);
            }
            List<String> texts = entries.stream().map(item -> {
                if (!(item instanceof Map<?, ?> entry) || !(entry.get("text") instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("Invalid Hansard excerpt for " + party);
                }
                return text;
            }).toList();
            result.put(party, texts);
            records.put(party,entries.stream().map(item -> {
                var entry=(Map<?,?>)item; Map<String,Object> metadata=new LinkedHashMap<>();
                metadata.put("speaker",entry.get("speaker")); metadata.put("date",entry.get("date")); metadata.put("text",entry.get("text"));
                metadata.put("textSha256",engine.utils.Hashes.sha256((String)entry.get("text")));
                return Collections.unmodifiableMap(metadata);
            }).toList());
        }
        excerptsByParty = Map.copyOf(result);
        recordsByParty=Map.copyOf(records);
    }

    public List<String> getExcerpts(Party party) { return excerptsByParty.get(party); }
    public List<Map<String,Object>> select(Party party,int count) {
        var available=recordsByParty.get(party); int chosen=count==-1 ? available.size() : count;
        if (chosen<0 || chosen>available.size()) throw new IllegalArgumentException("Requested "+chosen+" grounding excerpts for "+party+"; corpus has "+available.size());
        return List.copyOf(available.subList(0,chosen));
    }
}
