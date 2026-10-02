package engine;

import engine.application.*;
import engine.demo.DemoChatManager;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SittingCountBoundaryTest {
    @TempDir Path root;
    private static final String ROUNDS="Rounds must be an integer between 1 and 10";
    private static final String GROUNDING="Grounding count must be -1 (all) or an integer from 0 to 1000";
    private record Invalid(String json,String error) { }
    private static String settings(String rounds,String grounding,String member) {
        return "{\"rounds\":"+rounds+",\"groundingCount\":"+grounding+",\"agentModelPreset\":\"demo\","
                +"\"members\":[{\"party\":\"LABOUR\",\"groundingCount\":"+member+"}]}";
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> parsed(String text) { return (Map<String,Object>)Json.parse(text); }
    private static List<Invalid> invalid() {
        var cases=new ArrayList<Invalid>();
        for (String value:List.of("1.0000000000000000001","1.0","1e0","10.0000000000000000001"))
            cases.add(new Invalid(settings(value,"0","0"),ROUNDS));
        for (String value:List.of("1e-400","-1e-400","1.0000000000000000001","1000.0000000000000000001","1.0","1e0")) {
            cases.add(new Invalid(settings("1",value,"0"),GROUNDING));
            cases.add(new Invalid(settings("1","0",value),GROUNDING));
        }
        return cases;
    }

    @Test void countsRequireJsonIntegersBeforeExactBoundsConversion() throws Exception {
        var config=TestFixtures.copyResources(root).config();
        for (var sample:invalid()) {
            var error=assertThrows(IllegalArgumentException.class,() -> RunSpec.resolve(parsed(sample.json()),config));
            assertEquals(sample.error(),error.getMessage()); assertNull(error.getCause());
        }
        for (int count:List.of(-1,0,1,1000)) {
            var values=parsed(settings("10",String.valueOf(count),String.valueOf(count)));
            values.put("rounds",10L); values.put("groundingCount",(long)count);
            var resolved=RunSpec.resolve(values,config);
            assertEquals(10,resolved.rounds()); assertEquals(count,resolved.groundingCount());
            assertEquals(count,resolved.members().get(0).groundingCount());
        }
        for (long overflow:List.of(Long.MIN_VALUE,Long.MAX_VALUE,4294967297L)) {
            var values=parsed(settings("1","0","0")); values.put("rounds",overflow);
            assertEquals(ROUNDS,assertThrows(IllegalArgumentException.class,() -> RunSpec.resolve(values,config)).getMessage());
            values.put("rounds",1); values.put("groundingCount",overflow);
            assertEquals(GROUNDING,assertThrows(IllegalArgumentException.class,() -> RunSpec.resolve(values,config)).getMessage());
        }
        var defaults=RunSpec.resolve(Map.of("members",List.of(Map.of("party","LABOUR"))),config);
        assertEquals(config.defaultRounds(),defaults.rounds()); assertEquals(-1,defaults.groundingCount());
        assertNull(defaults.members().get(0).groundingCount());
    }

    @Test void apiRefusesRoundedCountsBeforeProviderConstructionAndRunPersistence() throws Exception {
        TestFixtures.copyResources(root); var calls=new AtomicInteger();
        var store=new RunStore(root.resolve("runs"));
        try (var app=new DebateApplication(root,store,model -> { calls.incrementAndGet(); return new DemoChatManager(); })) {
            var server=WebServer.start(0,app);
            try {
                var http=HttpClient.newHttpClient(); String base="http://localhost:"+server.getAddress().getPort();
                for (var sample:invalid()) {
                    var request=HttpRequest.newBuilder(URI.create(base+"/api/debates")).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(sample.json())).build();
                    var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                    assertEquals(400,response.statusCode()); assertEquals(Map.of("error",sample.error()),Json.parse(response.body()));
                    assertEquals(0,calls.get()); assertTrue(app.runs().isEmpty()); assertTrue(store.ids().isEmpty());
                }
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }

    @Test void browserCommandsAndGuidedSettingsRefusalPreserveThePreviousSettingsFile() throws Exception {
        var config=TestFixtures.copyResources(root).config(); var calls=new AtomicInteger();
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { calls.incrementAndGet(); return new DemoChatManager(); })) {
            app.settings().save("counts",parsed(settings("1","0","0")),config);
            Path existing=root.resolve("runs/settings/counts.json"); byte[] original=Files.readAllBytes(existing);
            Path input=root.resolve("fractional-settings.json"); Files.writeString(input,settings("1","1e-400","0"));
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                var request=HttpRequest.newBuilder(URI.create(base+"/api/settings/counts")).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Files.readString(input))).build();
                var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(400,response.statusCode()); assertEquals(Map.of("error",GROUNDING),Json.parse(response.body()));
                var output=new ByteArrayOutputStream(); var printer=new PrintStream(output,true,StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),printer);
                for (String[] command:List.of(new String[]{"settings","save","counts",input.toString()},new String[]{"start",input.toString()}))
                    assertEquals(GROUNDING,assertThrows(IllegalStateException.class,() -> cli.command(command)).getMessage());
                new Main(base,new Scanner("9\ncounts\n"+input+"\n11\ncounts\n"+input+"\n2\n0\n"),printer).menu();
                String guided=output.toString(StandardCharsets.UTF_8);
                assertTrue(guided.contains(GROUNDING)); assertTrue(guided.contains("[]")); assertFalse(guided.contains("Watch this sitting"));
                assertArrayEquals(original,Files.readAllBytes(existing)); assertEquals(0,calls.get()); assertTrue(app.runs().isEmpty());
                assertEquals(List.of("counts"),app.settings().names());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
