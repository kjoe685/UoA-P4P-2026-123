package engine.agent;

import engine.utils.FileTextReader;
import engine.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HansardExcerpts {

    private static final String EXCERPTS_PATH = "resources/data/HansardExcerpts.json";

    private final Map<String, List<String>> excerptsByParty = new LinkedHashMap<>();

    public HansardExcerpts(FileTextReader fileTextReader) {
        String json = fileTextReader.readText(EXCERPTS_PATH);
        Map<String, Object> root = castToMap(Json.parse(json));
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            List<String> texts = new ArrayList<>();
            for (Object item : castToList(entry.getValue())) {
                Map<String, Object> excerpt = castToMap(item);
                texts.add((String) excerpt.get("text"));
            }
            excerptsByParty.put(entry.getKey(), texts);
        }
    }

    public List<String> getExcerpts(Party party) {
        return excerptsByParty.getOrDefault(party.getDisplayName(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castToList(Object value) {
        return (List<Object>) value;
    }
}
