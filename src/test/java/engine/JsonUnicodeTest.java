package engine;

import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonUnicodeTest {
    @Test void rejectsUnpairedSurrogatesInNestedValuesAndKeysBeforeUtf8Serialization() {
        for (String invalid:List.of("{\"text\":\"\\ud800\"}","[\"\\udc00\"]","{\"\\ud800\":1}")) {
            var error=assertThrows(IllegalArgumentException.class,() -> Json.parse(invalid)); assertNull(error.getCause());
        }
        String unpaired=new String(new char[]{(char)0xd800});
        for (Object invalid:List.of(Map.of("text",unpaired),Map.of(unpaired,"value"),List.of(unpaired))) {
            assertThrows(IllegalArgumentException.class,() -> Json.write(invalid));
            assertThrows(IllegalArgumentException.class,() -> Json.writeCanonical(invalid));
        }
        try (var nlp=new engine.application.ManagedNlp(java.nio.file.Path.of("."))) {
            var error=assertThrows(IllegalArgumentException.class,() -> nlp.validateConfiguration("{\"stanceHypotheses\":{\"support\":\"\\ud800\"}}"));
            assertNull(error.getCause());
        }
    }
    @Test void canonicalOrderingAndValidUnicodeRemainStable() {
        String text="Tēnā 😀 e\u0301 \ufffd";
        var first=new LinkedHashMap<String,Object>(); first.put("z",Map.of("b",2,"a",text)); first.put("a",1);
        var second=new LinkedHashMap<String,Object>(); second.put("a",1); second.put("z",Map.of("a",text,"b",2));
        assertEquals(Json.writeCanonical(first),Json.writeCanonical(second));
        assertEquals(first,Json.parse(Json.write(first)));
        assertEquals("😀",Json.read("\"\\ud83d\\ude00\"",String.class));
        for (String invalid:List.of("","{} {}","{\"a\":1,\"a\":2}")) assertThrows(IllegalArgumentException.class,() -> Json.parse(invalid),"Strict JSON case: "+invalid);
    }
}
