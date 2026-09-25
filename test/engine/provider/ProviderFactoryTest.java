package engine.provider;

import engine.config.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFactoryTest {
    @TempDir Path directory;
    @Test void localProviderNeedsNoCredentialsAndCloudSelectionOnlyReadsItsOwnKey() {
        var requested = new ArrayList<String>();
        var factory = new ProviderFactory(name -> { requested.add(name); return name.endsWith("API_KEY") ? "KEY_SENTINEL" : null; }, directory.resolve("absent"));
        var local = new ModelConfig("ollama", "qwen3:8b", null, null, 100, 10);
        assertSame(factory.forModel(local), factory.forModel(local));
        assertFalse(requested.stream().anyMatch(name -> name.endsWith("API_KEY")));
        factory.forModel(new ModelConfig("gemini", "gemini-3.8-flash", null, null, 100, 10));
        assertTrue(requested.contains("GEMINI_API_KEY"));
        assertFalse(requested.contains("OPENAI_API_KEY"));
        factory.forModel(new ModelConfig("grok", "grok-4.7", null, null, 100, 10));
        assertTrue(requested.contains("XAI_API_KEY"));
    }
    @Test void environmentTakesPrecedenceOverLegacyFileAndErrorsAreSanitized() throws Exception {
        Path file = directory.resolve("key.txt");
        Files.writeString(file, "INVALID_KEY\nSENTINEL");
        var model = new ModelConfig("openai", "gpt-4o-mini", null, null, 100, 10);
        assertNotNull(new ProviderFactory(name -> "VALID_SENTINEL", file).forModel(model));
        var error = assertThrows(IllegalArgumentException.class, () -> new ProviderFactory(name -> null, file).forModel(model));
        assertFalse(error.getMessage().contains("SENTINEL"));
        assertNull(error.getCause());
    }
}
