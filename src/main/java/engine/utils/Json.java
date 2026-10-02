package engine.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** One strict JSON boundary for configuration, provider payloads, and public exports. */
public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private Json() { }

    public static Object parse(String input) { return read(input, Object.class); }

    public static <T> T read(String input, Class<T> type) {
        try {
            JsonNode tree=MAPPER.readTree(input);
            if (tree==null || tree.isMissingNode()) throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName());
            validateUnicode(tree);
            return MAPPER.treeToValue(tree,type);
        } catch (JsonProcessingException e) {
            // Diagnostics can contain private prompts or credentials from untrusted responses.
            throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName());
        }
    }

    public static String write(Object value) {
        try {
            validateUnicode(MAPPER.valueToTree(value));
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getSimpleName());
        }
    }

    public static String escape(String value) {
        String quoted = write(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    /** Stable map ordering for hashed settings and generated evaluator prompts. */
    public static String writeCanonical(Object value) {
        try {
            validateUnicode(MAPPER.valueToTree(value));
            return MAPPER.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getSimpleName());
        }
    }
    private static void validateUnicode(JsonNode value) {
        if (value==null) return;
        if (value.isTextual()) validText(value.textValue());
        else if (value.isObject()) value.properties().forEach(field -> {
            validText(field.getKey()); validateUnicode(field.getValue());
        });
        else if (value.isArray()) value.forEach(Json::validateUnicode);
    }
    private static void validText(String value) {
        for (int i=0;i<value.length();i++) {
            char current=value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i+1>=value.length() || !Character.isLowSurrogate(value.charAt(i+1)))
                    throw new IllegalArgumentException("JSON strings must contain valid Unicode scalar text");
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("JSON strings must contain valid Unicode scalar text");
            }
        }
    }
}
