package engine.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
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
            return MAPPER.readValue(input, type);
        } catch (JsonProcessingException e) {
            // Diagnostics can contain private prompts or credentials from untrusted responses.
            throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName());
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + value.getClass().getSimpleName());
        }
    }
}
