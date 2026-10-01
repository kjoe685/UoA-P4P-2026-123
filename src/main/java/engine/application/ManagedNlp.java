package engine.application;

import engine.evaluation.local.LocalEvaluationConfig;
import engine.utils.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional local runtime. Only explicit setup downloads dependencies; analysis never does. */
public final class ManagedNlp implements AutoCloseable {
    private final Path root;
    private final boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private Process service;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ManagedNlp(Path root) { this.root=root.toAbsolutePath().normalize(); }
    private Path python() { return root.resolve(windows ? ".runtime/nlp-env/Scripts/python.exe" : ".runtime/nlp-env/bin/python"); }
    private boolean installed() {
        try { return Files.isRegularFile(python()) && Files.readString(root.resolve(".runtime/nlp/setup.complete"))
                .equals(Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock")))); }
        catch (java.io.IOException e) { return false; }
    }
    public Map<String,Object> readiness(LocalEvaluationConfig config) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("installed",installed());
        try { result.put("service",health(config)); result.put("running",true); }
        catch (RuntimeException e) { result.put("running",false); }
        return result;
    }
    public void install(JobService.Context job) throws Exception {
        Files.createDirectories(root.resolve(".runtime/nlp"));
        var command=windows ? List.of("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",root.resolve("scripts/setup-nlp.ps1").toString())
                : List.of("sh",root.resolve("scripts/setup-nlp.sh").toString());
        var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve(".runtime/nlp/setup.log").toFile());
        stripCredentials(builder);
        Process setup=builder.start();
        try {
            while (!setup.waitFor(250,TimeUnit.MILLISECONDS)) job.checkCancelled();
            if (setup.exitValue()!=0) throw new IllegalStateException("Local dependency installation failed");
            job.checkCancelled(); AtomicFiles.write(root.resolve(".runtime/nlp/setup.complete"),Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock"))));
        } finally { if (setup.isAlive()) stop(setup); }
    }
    public synchronized void ensureRunning(LocalEvaluationConfig config) {
        try { health(config); return; } catch (RuntimeException ignored) { }
        if (!installed()) throw new IllegalStateException("Set up local VADER before evaluating");
        if (service!=null && service.isAlive()) throw new IllegalStateException("Managed local service configuration changed; restart the backend");
        try {
            Files.createDirectories(root.resolve(".runtime/nlp"));
            var builder=new ProcessBuilder(python().toString(),"-m","parliament_nlp.cli","serve","--config",root.resolve("nlp/config/models.json").toString(),
                    "--cache",root.resolve("nlp/.models").toString(),"--port",String.valueOf(config.endpoint().getPort()==-1 ? 80 : config.endpoint().getPort()))
                    .directory(root.resolve("nlp").toFile()).redirectErrorStream(true).redirectOutput(root.resolve(".runtime/nlp/service.log").toFile());
            stripCredentials(builder); service=builder.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime()<deadline && service.isAlive()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try { health(config); return; } catch (RuntimeException ignored) { }
                Thread.sleep(200);
            }
            throw new IllegalStateException("Local service did not become ready");
        } catch (Exception e) {
            if (service!=null) stop(service);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not start the local NLP service; check setup and port availability");
        }
    }
    private Map<?,?> health(LocalEvaluationConfig config) {
        try {
            var response=http.send(HttpRequest.newBuilder(config.endpoint().resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
            Object value=Json.parse(response.body());
            if (response.statusCode()!=200 || !(value instanceof Map<?,?> body) || !Integer.valueOf(2).equals(body.get("schemaVersion"))
                    || !"parliament-nlp".equals(body.get("service")) || !"0.2.0".equals(body.get("implementationVersion"))
                    || !Hashes.sha256(Files.readAllBytes(root.resolve("nlp/config/models.json"))).equals(body.get("configurationSha256")))
                throw new IllegalStateException("Incompatible local NLP service");
            return body;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Readiness interrupted"); }
        catch (Exception e) { throw new IllegalStateException("Local NLP service unavailable or incompatible"); }
    }
    private static void stripCredentials(ProcessBuilder builder) {
        for (String key:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY")) builder.environment().remove(key);
    }
    private static void stop(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly()); process.destroyForcibly();
    }
    @Override public synchronized void close() { if (service!=null && service.isAlive()) stop(service); }
}
