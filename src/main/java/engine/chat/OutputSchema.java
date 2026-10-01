package engine.chat;

import engine.utils.Json;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable schema; callers never share a mutable JSON tree with an adapter. */
public record OutputSchema(String name, String json) {
    public OutputSchema {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")
                || !Json.read(json, JsonNode.class).isObject())
            throw new IllegalArgumentException("Invalid output schema");
    }
    public Object value() { return Json.parse(json); }
}
