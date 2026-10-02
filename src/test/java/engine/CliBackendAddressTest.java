package engine;

import engine.application.*;
import engine.demo.DemoChatManager;
import engine.transcript.Transcript;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CliBackendAddressTest {
    @TempDir Path root;

    @Test void commandEntryPointUsesNormalizedEnvironmentAndSanitizesInvalidAddresses() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> {
            fail("Reading saved sittings cannot construct providers"); return null;
        })) {
            var server=WebServer.start(0,app);
            try {
                String address="HTTP://LOCALHOST:"+server.getAddress().getPort()+"/";
                Path log=root.resolve("terminal-entry.log");
                assertEquals(0,entryPoint(address,log)); assertEquals("[]",Files.readString(log).trim());
                assertNotEquals(0,entryPoint("http://PRIVATE_ADDRESS_SENTINEL@localhost:8080/",log));
                String diagnostic=Files.readString(log);
                assertTrue(diagnostic.contains("PARLIAMENT_URL must be a loopback HTTP root address"));
                assertFalse(diagnostic.contains("PRIVATE_ADDRESS_SENTINEL")); assertTrue(app.runs().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }

    private int entryPoint(String address,Path log) throws Exception {
        String classpath=String.join(File.pathSeparator,Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString()).toList());
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",windows ? "java.exe" : "java").toString(),
                "-cp",classpath,"engine.Main","runs").directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PARLIAMENT_URL",address);
        for (String key:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","HF_TOKEN","HUGGING_FACE_HUB_TOKEN")) builder.environment().remove(key);
        Process child=builder.start();
        try { assertTrue(child.waitFor(15,TimeUnit.SECONDS)); return child.exitValue(); }
        finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test void rootSlashWorksForCommandsGuidedEventsAndUnicodeExport() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> new DemoChatManager())) {
            var server=WebServer.start(0,app);
            try {
                var output=new ByteArrayOutputStream();
                var printer=new PrintStream(output,true,StandardCharsets.UTF_8);
                String base="http://localhost:"+server.getAddress().getPort()+"/";
                var cli=new Main(base,new Scanner(""),printer);
                cli.command(new String[]{"config"});
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("agentModelPreset"));
                var run=app.start(Map.of("topics",List.of("Housing ā 😀"),"rounds",1,
                        "members",List.of(Map.of("party","LABOUR")),"agentModelPreset","demo"));
                cli.command(new String[]{"watch",run.id()});
                assertEquals(Transcript.Outcome.COMPLETE,run.transcript().outcome());
                Path exported=root.resolve("exports/Public ā 😀.json");
                cli.command(new String[]{"transcript",run.id(),exported.toString()});
                assertEquals(run.transcript(),Json.read(Files.readString(exported),Transcript.class));
                output.reset();
                new Main(base,new Scanner("2\n3\n"+run.id()+"\n0\n"),printer).menu();
                String guided=output.toString(StandardCharsets.UTF_8);
                assertTrue(guided.contains(run.id()));
                assertTrue(guided.contains("Housing ā 😀"));
                assertFalse(guided.contains("Cannot read sitting events"));
                assertFalse(guided.contains("Operation failed"));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }

    @Test void invalidAddressesFailBeforeRequestsAndDoNotEchoConfiguredSecrets() throws Exception {
        Path destination=root.resolve("existing.txt"); Files.writeString(destination,"OWNER_CONTENT");
        List<String> invalid=Arrays.asList(null,"", "not a URL PRIVATE_ADDRESS_SENTINEL",
                "http://PRIVATE_ADDRESS_SENTINEL:password@localhost:8080/",
                "http://localhost:8080/?token=PRIVATE_ADDRESS_SENTINEL",
                "http://localhost:8080/#PRIVATE_ADDRESS_SENTINEL",
                "http://localhost:8080/private/PRIVATE_ADDRESS_SENTINEL",
                "http://example.invalid:8080/", "https://localhost:8080/", "//localhost:8080/",
                "http://localhost:0/", "http://localhost:65536/", "http://localhost:/",
                "http://localhost:8080//", "http://127.0.0.1.evil.invalid/",
                "http://localhost:8080/\uD800");
        for (String address:invalid) {
            var output=new ByteArrayOutputStream();
            var error=assertThrows(IllegalArgumentException.class,() -> {
                var cli=new Main(address,new Scanner(""),new PrintStream(output));
                cli.command(new String[]{"export","fixture",destination.toString()});
            });
            assertEquals("PARLIAMENT_URL must be a loopback HTTP root address with a valid port and no credentials, query or fragment",error.getMessage());
            assertNull(error.getCause());
            var diagnostic=new StringWriter(); error.printStackTrace(new PrintWriter(diagnostic));
            assertFalse(diagnostic.toString().contains("PRIVATE_ADDRESS_SENTINEL"));
            assertEquals("OWNER_CONTENT",Files.readString(destination)); assertEquals(0,output.size());
        }
    }

    @Test void loopbackCaseAndLiteralFormsRemainAccepted() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> {
            fail("Address configuration cannot construct a provider"); return null;
        })) {
            var server=WebServer.start(0,app);
            try {
                var output=new ByteArrayOutputStream();
                var cli=new Main("HTTP://LOCALHOST:"+server.getAddress().getPort()+"/",new Scanner(""),new PrintStream(output));
                cli.command(new String[]{"runs"}); assertEquals("[]",output.toString().trim());
                for (String address:List.of("http://127.0.0.1", "http://localhost:80/",
                        "http://[::1]:8080/", "http://[0:0:0:0:0:0:0:1]:65535"))
                    assertDoesNotThrow(() -> new Main(address,new Scanner(""),new PrintStream(output)));
                assertTrue(app.runs().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
