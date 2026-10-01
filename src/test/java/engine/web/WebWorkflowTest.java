package engine.web;

import com.sun.net.httpserver.HttpServer;
import engine.utils.Json;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class WebWorkflowTest {
    @Test void demoNeedsNoKeyAndSupportsReplayAndChair() throws Exception {
        HttpServer server = WebServer.start(0, key -> { throw new AssertionError("Demo called live provider"); });
        String root = "http://localhost:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();
        try {
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(root)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var create = post(client, root + "/api/debates", "{\"topics\":[\"First\",\"Second\"],\"rounds\":2,\"provider\":\"demo\",\"members\":[{\"party\":\"LABOUR\"}]}");
            assertEquals(201, create.statusCode());
            String id = (String) ((Map<?, ?>) Json.parse(create.body())).get("id");
            assertEquals(202, post(client, root + "/api/debates/" + id + "/speaker", "{\"message\":\"Return to the question\"}").statusCode());
            String eventsUrl = root + "/api/debates/" + id + "/events";
            var events = client.send(HttpRequest.newBuilder(URI.create(eventsUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(events.body().contains("Return to the question"));
            assertTrue(events.body().contains("\"type\":\"speech\""));
            assertTrue(events.body().contains("\"outcome\":\"complete\""));
            var replay = client.send(HttpRequest.newBuilder(URI.create(eventsUrl)).header("Last-Event-ID", "0").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(replay.body().contains("id: 0\n"));
            assertTrue(replay.body().contains("id: 1\n"));
            assertEquals(409, post(client, root + "/api/debates/" + id + "/speaker", "{\"message\":\"Late ruling\"}").statusCode());
        } finally { server.stop(0); ((ExecutorService) server.getExecutor()).shutdownNow(); }
    }

    static HttpResponse<String> post(HttpClient client, String url, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
