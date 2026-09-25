package engine.provider;

import engine.utils.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Shared bounded transport. Only explicit rate-limit rejections are retried, once. */
public final class ProviderHttp {
    private ProviderHttp() { }
    public static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static String credential(String key) {
        if (key == null || key.isBlank() || key.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("A valid API key is required");
        return key;
    }
    public static String post(HttpClient client, URI endpoint, Map<String, String> headers,
                              Object body, int timeoutSeconds, String label) {
        String payload = Json.write(body);
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException();
                var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                        .header("Content-Type", "application/json");
                headers.forEach(builder::header);
                var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) return response.body();
                if (response.statusCode() == 429 && attempt == 0) {
                    long delay = 250;
                    try { delay = Math.max(250, Math.multiplyExact(Long.parseLong(response.headers().firstValue("Retry-After").orElse("0")), 1000)); }
                    catch (NumberFormatException | ArithmeticException ignored) { delay = 3000; }
                    if (delay <= 2000 && deadline - System.nanoTime() > Duration.ofMillis(delay).toNanos()) {
                        Thread.sleep(delay);
                        continue;
                    }
                }
                throw new IllegalStateException(label + " request failed (HTTP " + response.statusCode() + ")");
            }
            throw new IllegalStateException(label + " request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " request interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Could not contact " + label + " (connection failed or timed out)");
        }
    }
}
