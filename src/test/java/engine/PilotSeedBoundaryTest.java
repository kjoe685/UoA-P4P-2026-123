package engine;

import engine.application.*;
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
import static org.junit.jupiter.api.Assertions.*;

class PilotSeedBoundaryTest {
    @TempDir Path root;
    private static final String ERROR="Preparation seed needs a whole number between -9223372036854775808 and 9223372036854775807";
    private static HttpResponse<String> prepare(HttpClient http,String url,String text) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(text)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void exactSignedSeedsRetainBlankPreparationsAcrossHttpCommandAndMenu() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Preparation cannot infer"); return null; })) {
            String source=(String)app.pilots().importDataset(PilotTest.body(PilotTest.fixture("unreviewed",240))).get("id");
            Path file=root.resolve("runs/pilots/"+source+".json"); byte[] original=Files.readAllBytes(file);
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient();
                for (long seed:List.of(9007199254740993L,Long.MIN_VALUE,Long.MAX_VALUE)) {
                    var response=prepare(http,base+"/api/pilots/"+source+"/prepare","{\"seed\":"+seed+"}"); assertEquals(201,response.statusCode());
                    String id=(String)((Map<?,?>)Json.parse(response.body())).get("id"); var prepared=app.pilots().read(id);
                    assertEquals(seed,prepared.preparation().seed()); assertEquals(200,prepared.rows().size());
                    assertTrue(prepared.rows().stream().allMatch(row -> row.reviewer().isEmpty() && row.sentiment().isEmpty() && row.stance().isEmpty()));
                    String again=(String)app.pilots().prepare(source,Map.of("seed",seed)).get("id"); assertEquals(prepared,app.pilots().read(again));
                }
                var output=new ByteArrayOutputStream(); var printer=new PrintStream(output,true,StandardCharsets.UTF_8);
                new Main(base,new Scanner(""),printer).command(new String[]{"pilot","prepare",source,"9007199254740993"});
                String commandId=(String)((Map<?,?>)Json.parse(output.toString(StandardCharsets.UTF_8))).get("id");
                assertEquals(9007199254740993L,app.pilots().read(commandId).preparation().seed());
                Set<String> before=new HashSet<>(app.pilots().list().stream().map(item -> (String)item.get("id")).toList());
                output.reset(); new Main(base,new Scanner("17\nprepare\n"+source+"\n-9007199254740993\n0\n"),printer).menu();
                var added=app.pilots().list().stream().map(item -> (String)item.get("id")).filter(id -> !before.contains(id)).toList();
                assertEquals(1,added.size()); assertEquals(-9007199254740993L,app.pilots().read(added.get(0)).preparation().seed());
                assertArrayEquals(original,Files.readAllBytes(file));
                int count=app.pilots().list().size();
                for (String value:List.of("9223372036854775808","-9223372036854775809","1.0000000000000000001","1e-400","1e0")) {
                    var refused=prepare(http,base+"/api/pilots/"+source+"/prepare","{\"seed\":"+value+"}");
                    assertEquals(400,refused.statusCode()); assertEquals(count,app.pilots().list().size()); assertArrayEquals(original,Files.readAllBytes(file));
                }
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void invalidTerminalSeedsAreSanitizedBeforeAnyPreparationOrRequest() throws Exception {
        var output=new ByteArrayOutputStream(); var printer=new PrintStream(output,true,StandardCharsets.UTF_8);
        // No server is listening; a request would be an IO failure, rather than this static validation error.
        var cli=new Main("http://127.0.0.1:1",new Scanner(""),printer);
        for (String value:List.of("PRIVATE_SEED_SENTINEL","1.0000000000000000001","1e-400","9223372036854775808")) {
            var error=assertThrows(IllegalArgumentException.class,() -> cli.command(new String[]{"pilot","prepare","source",value}));
            assertEquals(ERROR,error.getMessage()); assertNull(error.getCause());
            var diagnostic=new StringWriter(); error.printStackTrace(new PrintWriter(diagnostic)); assertFalse(diagnostic.toString().contains("PRIVATE_SEED_SENTINEL"));
        }
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Invalid seed cannot infer"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                new Main("http://localhost:"+server.getAddress().getPort(),new Scanner("17\nprepare\nsource\nPRIVATE_SEED_SENTINEL\n0\n"),printer).menu();
                String guided=output.toString(StandardCharsets.UTF_8); assertTrue(guided.contains(ERROR)); assertFalse(guided.contains("PRIVATE_SEED_SENTINEL"));
                assertTrue(app.pilots().list().isEmpty()); assertTrue(app.runs().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
