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
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws java.io.IOException; }
    private final Path root;
    private final Starter starter;
    private final boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private Process service;
    private Path serviceSettings;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ManagedNlp(Path root) { this(root,ProcessBuilder::start); }
    ManagedNlp(Path root,Starter starter) { this.root=root.toAbsolutePath().normalize(); this.starter=starter; }
    private Path python() { return root.resolve(windows ? ".runtime/nlp-env/Scripts/python.exe" : ".runtime/nlp-env/bin/python"); }
    private boolean installed() {
        try { return Files.isRegularFile(python()) && Files.readString(root.resolve(".runtime/nlp/setup.complete"))
                .equals(Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock")))); }
        catch (java.io.IOException e) { return false; }
    }
    public Map<String,Object> readiness(LocalEvaluationConfig config) {
        try { return readiness(config,Files.readString(root.resolve("nlp/config/models.json"))); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read local model configuration"); }
    }
    public Map<String,Object> readiness(LocalEvaluationConfig config,String settings) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("installed",installed());
        try { result.put("service",health(config,Hashes.sha256(settings))); result.put("running",true); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (RuntimeException e) { result.put("running",false); }
        return result;
    }
    public void install(JobService.Context job) throws Exception {
        install(job,false);
    }
    private void install(JobService.Context job,boolean models) throws Exception {
        close();
        Files.createDirectories(root.resolve(".runtime/nlp"));
        var command=new ArrayList<>(windows ? List.of("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",root.resolve("scripts/setup-nlp.ps1").toString())
                : List.of("sh",root.resolve("scripts/setup-nlp.sh").toString()));
        if (models) command.add(windows ? "-Models" : "models");
        var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve(".runtime/nlp/setup.log").toFile());
        stripCredentials(builder);
        job.checkCancelled(); Process setup=starter.start(builder);
        try {
            while (!setup.waitFor(250,TimeUnit.MILLISECONDS)) job.checkCancelled();
            if (setup.exitValue()!=0) throw new IllegalStateException("Local dependency installation failed");
            job.checkCancelled(); AtomicFiles.write(root.resolve(".runtime/nlp/setup.complete"),Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock"))));
            if (models) AtomicFiles.write(root.resolve(".runtime/nlp/models.complete"),Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock"))));
        } finally { if (setup.isAlive()) stop(setup); }
    }
    public void download(JobService.Context job,String method,String settings) throws Exception {
        if (!Set.of("cardiff-sentiment","deberta-stance").contains(method)) throw new IllegalArgumentException("Choose a local transformer method");
        job.update("Installing locked CPU model dependencies",null); install(job,true); job.checkCancelled();
        Path frozen=root.resolve(".runtime/nlp/download-"+UUID.randomUUID()+".json"); AtomicFiles.write(frozen,settings);
        job.update("Downloading pinned files for "+method,null);
        var builder=new ProcessBuilder(python().toString(),"-m","parliament_nlp.cli","download","--methods",method,
                "--config",frozen.toString(),"--cache",root.resolve("nlp/.models").toString())
                .directory(root.resolve("nlp").toFile()).redirectErrorStream(true).redirectOutput(root.resolve(".runtime/nlp/download.log").toFile());
        stripCredentials(builder); builder.environment().put("HF_HUB_DISABLE_SYMLINKS_WARNING","1");
        job.checkCancelled(); Process download=starter.start(builder);
        try {
            while (!download.waitFor(250,TimeUnit.MILLISECONDS)) job.checkCancelled();
            if (download.exitValue()!=0) throw new IllegalStateException("Pinned model download failed");
        } finally { if (download.isAlive()) stop(download); Files.deleteIfExists(frozen); }
    }
    public void validateConfiguration(String text) {
        Json.parse(text); // Validate embedded JSON scalar text before passing a settings file to Python.
        if (!installed()) throw new IllegalStateException("Set up local NLP before editing model-service settings");
        Path frozen=root.resolve(".runtime/nlp/validate-"+UUID.randomUUID()+".json");
        Process validation=null;
        try {
            AtomicFiles.write(frozen,text);
            var builder=new ProcessBuilder(python().toString(),"-m","parliament_nlp.cli","validate","--config",frozen.toString()).directory(root.resolve("nlp").toFile());
            stripCredentials(builder); builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            checkInterrupted(); validation=starter.start(builder);
            if (!validation.waitFor(10,TimeUnit.SECONDS)) { stop(validation); throw new IllegalStateException("Local configuration validation timed out"); }
            if (validation.exitValue()!=0) throw new IllegalArgumentException("Invalid local model configuration");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Validation interrupted"); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not validate local configuration"); }
        finally {
            if (validation!=null && validation.isAlive()) stop(validation);
            try { Files.deleteIfExists(frozen); } catch (java.io.IOException ignored) { }
        }
    }
    public synchronized void ensureRunning(LocalEvaluationConfig config) {
        try { ensureRunning(config,Files.readString(root.resolve("nlp/config/models.json"))); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read local model configuration"); }
    }
    public synchronized void ensureRunning(LocalEvaluationConfig config,String settings) {
        checkInterrupted();
        String expectedHash=Hashes.sha256(settings);
        try { health(config,expectedHash); return; }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (RuntimeException ignored) { }
        checkInterrupted();
        if (!installed()) throw new IllegalStateException("Set up local VADER before evaluating");
        close();
        try {
            Files.createDirectories(root.resolve(".runtime/nlp"));
            serviceSettings=root.resolve(".runtime/nlp/service-"+UUID.randomUUID()+".json"); AtomicFiles.write(serviceSettings,settings);
            var builder=new ProcessBuilder(python().toString(),"-m","parliament_nlp.cli","serve","--config",serviceSettings.toString(),
                    "--cache",root.resolve("nlp/.models").toString(),"--port",String.valueOf(config.endpoint().getPort()==-1 ? 80 : config.endpoint().getPort()))
                    .directory(root.resolve("nlp").toFile()).redirectErrorStream(true).redirectOutput(root.resolve(".runtime/nlp/service.log").toFile());
            stripCredentials(builder); builder.environment().put("HF_HUB_OFFLINE","1");
            builder.environment().put("TRANSFORMERS_OFFLINE","1"); builder.environment().put("HF_HUB_DISABLE_TELEMETRY","1");
            checkInterrupted(); service=starter.start(builder);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime()<deadline && service.isAlive()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try { health(config,expectedHash); return; }
                catch (java.util.concurrent.CancellationException e) { throw e; }
                catch (RuntimeException ignored) { }
                Thread.sleep(200);
            }
            throw new IllegalStateException("Local service did not become ready");
        } catch (Exception e) {
            close();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not start the local NLP service; check setup and port availability");
        }
    }
    private Map<?,?> health(LocalEvaluationConfig config,String expectedHash) {
        try {
            checkInterrupted();
            var response=HttpBodies.send(http,HttpRequest.newBuilder(config.endpoint().resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build(),Duration.ofSeconds(2),1_000_000);
            if (response.statusCode()!=200) throw new IllegalStateException("Incompatible local NLP service");
            Object value=Json.parse(response.body());
            if (response.statusCode()!=200 || !(value instanceof Map<?,?> body) || !Integer.valueOf(2).equals(body.get("schemaVersion"))
                    || !"parliament-nlp".equals(body.get("service")) || !engine.evaluation.local.NlpResponse.IMPLEMENTATION_VERSION.equals(body.get("implementationVersion"))
                    || !expectedHash.equals(body.get("configurationSha256")) || !validReadiness(body))
                throw new IllegalStateException("Incompatible local NLP service");
            return body;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Local readiness interrupted"); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Local NLP service unavailable or incompatible"); }
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Local operation interrupted");
    }
    private static boolean validReadiness(Map<?,?> body) {
        Set<String> methods=Set.of("vader-sentiment","cardiff-sentiment","deberta-stance");
        if (!body.keySet().equals(Set.of("status","schemaVersion","service","implementationVersion","configurationSha256","methods","readiness"))
                || !"ok".equals(body.get("status")) || !(body.get("methods") instanceof List<?> selected)
                || !methods.containsAll(selected) || new HashSet<>(selected).size()!=selected.size()
                || !(body.get("readiness") instanceof Map<?,?> readiness) || !methods.containsAll(readiness.keySet())) return false;
        for (var entry:readiness.entrySet()) {
            if (!(entry.getValue() instanceof Map<?,?> state)
                    || !(state.get("dependenciesPresent") instanceof Boolean) || !(state.get("filesCached") instanceof Boolean)
                    || !(state.get("loaded") instanceof Boolean)) return false;
            if (entry.getKey().equals("vader-sentiment")) {
                if (!state.keySet().equals(Set.of("dependenciesPresent","filesCached","loaded"))) return false;
            } else if (!state.keySet().equals(Set.of("dependenciesPresent","filesCached","loaded","modelId","revision"))
                    || !(state.get("modelId") instanceof String model) || !model.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
                    || !(state.get("revision") instanceof String revision) || !revision.matches("[0-9a-f]{40}")) return false;
        }
        return true;
    }
    private static void stripCredentials(ProcessBuilder builder) {
        for (String key:List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","HF_TOKEN","HUGGING_FACE_HUB_TOKEN")) builder.environment().remove(key);
    }
    private static void stop(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly()); process.destroyForcibly();
    }
    @Override public synchronized void close() {
        if (service!=null && service.isAlive()) stop(service); service=null;
        if (serviceSettings!=null) try { Files.deleteIfExists(serviceSettings); } catch (java.io.IOException ignored) { }
        serviceSettings=null;
    }
}
