package engine;

import engine.config.ConfigurationSnapshot;
import java.nio.file.*;
import java.io.IOException;
import java.util.Map;

public final class TestFixtures {
    private TestFixtures() { }
    public static ConfigurationSnapshot copyResources(Path root) throws IOException {
        for (String directory:new String[]{"config","prompts","data/hansard"}) {
            try (var files=Files.walk(Path.of(directory))) {
                for (var source:files.filter(Files::isRegularFile).toList()) {
                    Path destination=root.resolve(source); Files.createDirectories(destination.getParent()); Files.copy(source,destination);
                }
            }
        }
        return ConfigurationSnapshot.load(root);
    }
    public static Map<String,Object> settings() {
        return Map.of("topics",java.util.List.of("Housing","Climate"),"rounds",2,
                "members",java.util.List.of(Map.of("party","LABOUR"),Map.of("party","NATIONAL","strategy","STRAW_MAN")),"agentModelPreset","demo");
    }
}
