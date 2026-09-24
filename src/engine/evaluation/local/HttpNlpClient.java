package engine.evaluation.local;

import engine.utils.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Stateless transport for the private NLP service. Error bodies never reach reports or logs. */
public final class HttpNlpClient implements NlpClient {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public HttpNlpClient(URI endpoint, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(), endpoint, timeout);
    }

    public HttpNlpClient(HttpClient client, URI endpoint, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.timeout = Objects.requireNonNull(timeout);
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || endpoint.getQuery() != null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Invalid private NLP endpoint or timeout");
        }
    }

    @Override public NlpResponse analyze(NlpRequest request) {
        try {
            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Local NLP request failed (HTTP " + response.statusCode() + ")");
            try {
                return Json.read(response.body(), NlpResponse.class);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Local NLP returned an invalid response");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local NLP request interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Local NLP service unavailable or timed out");
        }
    }
}
