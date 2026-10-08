package engine.application;

import engine.TestFixtures;
import engine.config.*;
import engine.utils.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaConfigurationTest {
    @org.junit.jupiter.api.Test void optionalThinkingPreservesLegacyStrictnessAndFrozenConfiguration() throws Exception {
        var legacy=engine.config.OllamaConfig.defaults();
        String old="{\"schemaVersion\":1,\"baseUrl\":\"http://127.0.0.1:11434\",\"contextTokens\":16384,\"startupTimeoutSeconds\":30,\"downloadTimeoutSeconds\":7200}";
        assertEquals(legacy,Json.read(old,engine.config.OllamaConfig.class));
        var disabled=Json.read(old.substring(0,old.length()-1)+",\"thinkingEnabled\":false}",engine.config.OllamaConfig.class);
        assertFalse(disabled.thinkingEnabled());
        assertFalse(disabled.effective(key -> key.equals("OLLAMA_CONTEXT_TOKENS") ? "32768" : null).thinkingEnabled());
        assertEquals(disabled,Json.read(Json.write(disabled),engine.config.OllamaConfig.class));
        for(String value:java.util.List.of("0","1","\"false\"","[]","{}"))
            assertThrows(IllegalArgumentException.class,() -> Json.read(old.substring(0,old.length()-1)+",\"thinkingEnabled\":"+value+"}",engine.config.OllamaConfig.class));
        Path root=java.nio.file.Files.createTempDirectory("thinking-config"); TestFixtures.copyResources(root);
        var before=ConfigurationSnapshot.load(root); var assets=new AssetService(root);
        assets.update("config/ollama.json",Json.write(disabled),true);
        assertFalse(ConfigurationSnapshot.load(root).ollama().thinkingEnabled());
        assertNull(before.ollama().thinkingEnabled());
        assertTrue(ConfigurationSnapshot.load(root).sourceContents().get("selection/ollama-settings.json").contains("\"thinkingEnabled\":false"));
    }
    @TempDir Path root;
    @Test void sharedEditorValidatesLocalSettingsAndRejectsSecretsNonloopbackAndInvalidLimits() throws Exception {
        var before=TestFixtures.copyResources(root); var assets=new AssetService(root);
        var local=new OllamaConfig(1,URI.create("http://127.0.0.1:12345"),8192,5,30); String valid=Json.write(local);
        assertTrue(assets.paths().contains("config/ollama.json")); assets.update("config/ollama.json",valid,false); assertEquals(before.ollama(),ConfigurationSnapshot.load(root).ollama());
        assets.update("config/ollama.json",valid,true); assertEquals(local,ConfigurationSnapshot.load(root).ollama()); assertNotEquals(local,before.ollama());
        for (String invalid:List.of(valid.replace("127.0.0.1","example.com"),valid.replace("http://","https://"),valid.replace("127.0.0.1","SECRET_SENTINEL@127.0.0.1"),
                valid.replace("8192","0"),valid.replace("\"downloadTimeoutSeconds\":30","\"downloadTimeoutSeconds\":0"),valid.replace("}",",\"apiKey\":\"SECRET_SENTINEL\"}"))) {
            var error=assertThrows(IllegalArgumentException.class,() -> assets.update("config/ollama.json",invalid,true)); assertFalse(error.getMessage().contains("SECRET_SENTINEL")); assertEquals(valid,assets.read("config/ollama.json"));
        }
        assertEquals(local.effective(key -> key.equals("OLLAMA_CONTEXT_TOKENS") ? "16384" : null).contextTokens(),16384);
        assertThrows(IllegalArgumentException.class,() -> local.effective(key -> key.equals("OLLAMA_BASE_URL") ? "http://SECRET_SENTINEL@localhost:11434" : null));
        for (String model:List.of("x:cloud","x-cloud","x:CLOUD","x:Cloud","x:8b-CLOUD","x:cloud:local","x:local:local","../model","x\nSECRET","x?key=secret")) assertThrows(IllegalArgumentException.class,() -> OllamaConfig.requireLocalModel(model));
        for (String model:List.of("qwen3:8b","namespace/model:v1","model","qwen3:8b:local","model:LOCAL")) OllamaConfig.requireLocalModel(model);
    }
}
