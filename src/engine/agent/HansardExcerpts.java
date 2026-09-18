package engine.agent;

import engine.utils.Json;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The existing small corpus, frozen at run start. Speaker metadata never enters a prompt. */
public final class HansardExcerpts {
    private final Map<Party, List<String>> excerptsByParty;

    public HansardExcerpts(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Hansard must be an object");
        Map<Party, List<String>> result = new EnumMap<>(Party.class);
        for (Party party : Party.values()) {
            Object raw = root.get(party.name());
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
        }
        excerptsByParty = Map.copyOf(result);
    }

    public List<String> getExcerpts(Party party) { return excerptsByParty.get(party); }
}
