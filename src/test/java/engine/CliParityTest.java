package engine;

import engine.application.*;
import engine.demo.DemoChatManager;
import engine.transcript.Transcript;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class CliParityTest {
    @TempDir Path root;
    @Test void commandsAndGuidedMenuUseSameValidationPersistenceAndExportsAsBrowser() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> new DemoChatManager())) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                var output=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),output);
                Path settings=root.resolve("settings.json");
                Files.writeString(settings,Json.write(Map.of("topics",List.of("Housing"),"rounds",1,"members",List.of(Map.of("party","LABOUR")),"agentModelPreset","demo")));
                cli.command(new String[]{"start",settings.toString()});
                String id=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset();
                cli.command(new String[]{"watch",id});
                assertTrue(bytes.toString().contains("\"outcome\":\"complete\"")); bytes.reset();
                cli.command(new String[]{"runs"}); assertTrue(bytes.toString().contains(id)); bytes.reset();
                Path transcript=root.resolve("public.json"), text=root.resolve("public.txt");
                cli.command(new String[]{"transcript",id,transcript.toString()});
                cli.command(new String[]{"export",id,text.toString()});
                assertEquals(app.find(id).transcript(),Json.read(Files.readString(transcript),Transcript.class));
                assertEquals(app.textExport(id),Files.readString(text));
                assertFalse(Files.readString(transcript).contains("strategy"));
                cli.command(new String[]{"import",transcript.toString()});
                assertEquals(2,app.runs().size());
                String guided="1\n\n\nTransport\n1\nLABOUR\n\n\n2\n0\n";
                new Main(base,new Scanner(guided),output).menu();
                assertEquals(3,app.runs().size());
                assertTrue(app.runs().stream().anyMatch(run -> run.topics().get(0).title().equals("Transport")));
                Files.writeString(settings,"{\"rounds\":1.5,\"members\":[{\"party\":\"LABOUR\"}]}");
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"start",settings.toString()}));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
