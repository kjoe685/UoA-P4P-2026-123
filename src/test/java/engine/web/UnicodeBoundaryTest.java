package engine.web;

import engine.TestFixtures;
import engine.application.*;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class UnicodeBoundaryTest {
    @TempDir Path root;
    @Test void malformedUtf8AndUnpairedEscapesCannotMutateSavedSettings() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("No inference"); return null; })) {
            var server=WebServer.start(0,app); var client=HttpClient.newHttpClient();
            try {
                String endpoint="http://localhost:"+server.getAddress().getPort()+"/api/settings/fixture";
                var settings=new LinkedHashMap<>(TestFixtures.settings()); settings.put("topics",List.of("INPUT_SECRET_SENTINEL X("));
                byte[] malformed=Json.write(settings).getBytes(StandardCharsets.UTF_8);
                for (int i=0;i<malformed.length;i++) if (malformed[i]=='X') { malformed[i]=(byte)0xc3; break; }
                String escaped=Json.write(settings).replace("X(","\\ud800");
                for (byte[] body:List.of(malformed,escaped.getBytes(StandardCharsets.UTF_8))) {
                    var response=client.send(HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(400,response.statusCode()); assertFalse(response.body().contains("SENTINEL"));
                    assertTrue(app.settings().names().isEmpty()); assertTrue(app.runs().isEmpty());
                }
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void validScalarTextAndPairedEscapesRoundTripWithoutNormalization() throws Exception {
        TestFixtures.copyResources(root); String evidence="Tēnā 😀 e\u0301 \ufffd";
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("No inference"); return null; })) {
            var server=WebServer.start(0,app); var client=HttpClient.newHttpClient();
            try {
                String endpoint="http://localhost:"+server.getAddress().getPort()+"/api/settings/fixture";
                var settings=new LinkedHashMap<>(TestFixtures.settings()); settings.put("topics",List.of(evidence));
                String body=Json.write(settings).replace("😀","\\ud83d\\ude00");
                var response=client.send(HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());
                assertEquals(evidence,((List<?>)app.settings().read("fixture").get("topics")).get(0) instanceof Map<?,?> topic ? topic.get("title") : null);
                var read=client.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertTrue(read.body().contains(evidence));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
