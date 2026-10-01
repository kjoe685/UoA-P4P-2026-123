package engine.application;

import engine.TestFixtures;
import engine.config.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedSettingsTest {
    @TempDir Path root;
    @Test void namedSettingsPrecedenceKeepsIndependentJudgeAndExplicitMemberOverrides() throws Exception {
        var config=TestFixtures.copyResources(root).config();
        var service=new SettingsService(root.resolve("runs/settings"));
        var input=new LinkedHashMap<>(TestFixtures.settings());
        input.put("members",List.of(Map.of("party","LABOUR"),Map.of("party","NATIONAL","strategy","STRAW_MAN","modelPreset","grok")));
        service.save("baseline",input,config);
        assertEquals(List.of("baseline"),service.names());
        var resolved=RunSpec.resolve(service.resolve(Map.of("settingsName","baseline","rounds",1,"agentModelPreset","gemini-flash")),config);
        assertEquals(1,resolved.rounds()); assertEquals("gpt-5-nano",resolved.evaluatorModelPreset());
        assertEquals("gemini-flash",resolved.members().get(0).modelPreset());
        assertEquals("grok",resolved.members().get(1).modelPreset());
        var restarted=new SettingsService(root.resolve("runs/settings"));
        assertEquals(service.read("baseline"),restarted.read("baseline"));
        assertThrows(IllegalArgumentException.class,() -> service.save("../escape",input,config));
        String original=Files.readString(root.resolve("runs/settings/baseline.json"));
        var invalid=new LinkedHashMap<>(input); invalid.put("apiKey","SECRET_SENTINEL");
        assertThrows(IllegalArgumentException.class,() -> service.save("baseline",invalid,config));
        assertEquals(original,Files.readString(root.resolve("runs/settings/baseline.json")));
        assertFalse(original.contains("SECRET_SENTINEL"));
    }
    @Test void invalidAssetsNeverReplaceFilesAndValidChangesReloadWithoutChangingOldSnapshot() throws Exception {
        var snapshot=TestFixtures.copyResources(root);
        var service=new AssetService(root);
        String persona=service.read("prompts/PoliticianPrompt.txt");
        assertThrows(IllegalArgumentException.class,() -> service.update("prompts/PoliticianPrompt.txt",persona+" {{UNKNOWN}}",true));
        assertEquals(persona,service.read("prompts/PoliticianPrompt.txt"));
        assertThrows(IllegalArgumentException.class,() -> service.read("keys/openAi/OpenAI_Key.txt"));
        assertThrows(IllegalArgumentException.class,() -> service.read("../config/engine.json"));
        String original=service.read("config/engine.json");
        String updated=original.replace("\"defaultRounds\": 3","\"defaultRounds\": 2");
        service.update("config/engine.json",updated,false);
        assertEquals(original,service.read("config/engine.json"));
        service.update("config/engine.json",updated,true);
        assertEquals(2,ConfigurationSnapshot.load(root).config().defaultRounds());
        assertEquals(3,snapshot.config().defaultRounds());
        assertNotEquals(snapshot.sourceHashes().get("config/engine.json"),ConfigurationSnapshot.load(root).sourceHashes().get("config/engine.json"));
        String unsupported=updated.replace("\"provider\": \"gemini\"","\"provider\": \"unsupported\"");
        assertThrows(IllegalArgumentException.class,() -> service.update("config/engine.json",unsupported,true));
        assertEquals(updated,service.read("config/engine.json"));
        try (var files=Files.walk(root)) { assertFalse(files.anyMatch(file -> file.toString().endsWith(".part"))); }
    }
}
