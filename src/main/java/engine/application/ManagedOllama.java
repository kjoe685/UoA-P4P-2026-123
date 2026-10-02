package engine.application;

import com.fasterxml.jackson.databind.JsonNode;
import engine.config.OllamaConfig;
import engine.utils.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One local service owner shared by debate, blind evaluation and explicit setup jobs. */
public final class ManagedOllama implements AutoCloseable {
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws IOException; }
    private record Model(String name,long bytes,String digest,boolean local) { }
    private record Service(String version,List<Model> models) { }
    private final Path root;
    private final OllamaInstaller installer;
    private final Starter starter;
    private final Map<URI,Process> owned=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public ManagedOllama(Path root) { this(root,OllamaPackage.current(),ProcessBuilder::start); }
    ManagedOllama(Path root,OllamaPackage artifact,Starter starter) {
        this.root=root.toAbsolutePath().normalize(); this.installer=new OllamaInstaller(root,artifact); this.starter=starter;
    }
    public Map<String,Object> readiness(OllamaConfig config) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("installed",installer.installed());
        result.put("runtimePackage",installer.metadata()); result.put("settings",config); result.put("owned",isOwned(config));
        try {
            var service=health(config); result.put("running",true); result.put("version",service.version());
            result.put("models",service.models()); result.put("message","Compatible local service responds; cached models have not been behaviourally verified");
        } catch (RuntimeException e) { result.put("running",false); result.put("models",List.of()); result.put("message","Local service unavailable or incompatible; Ollama0.35.0 or newer is required"); }
        return result;
    }
    private boolean isOwned(OllamaConfig config) { var process=owned.get(config.baseUrl()); return process!=null && process.isAlive(); }
    public void install(OllamaConfig config,JobService.Context job) throws Exception { installer.install(config,job); }
    public synchronized void ensureRunning(OllamaConfig config) {
        if (closed) throw new IllegalStateException("Local runtime owner is closed");
        try { health(config); return; } catch (RuntimeException e) { checkInterrupted(); }
        if (occupied(config)) throw new IllegalStateException("Configured Ollama port is occupied by an incompatible service; use Ollama0.35.0 or newer");
        if (!installer.installed()) throw new IllegalStateException("Set up the local LLM runtime explicitly before using it");
        Process process=null;
        try {
            Path models=root.resolve(".runtime/ollama-models"); Files.createDirectories(models);
            var builder=new ProcessBuilder(installer.executable().toString(),"serve").directory(installer.directory().toFile())
                    .redirectErrorStream(true).redirectOutput(root.resolve(".runtime/ollama-service-"+port(config)+".log").toFile());
            builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("OLLAMA_") || Set.of(
                    "OPENAI_API_KEY","ANTHROPIC_API_KEY","GEMINI_API_KEY","XAI_API_KEY","HF_TOKEN","HUGGING_FACE_HUB_TOKEN").contains(key.toUpperCase(Locale.ROOT)));
            builder.environment().put("OLLAMA_HOST",config.baseUrl().toString());
            builder.environment().put("OLLAMA_MODELS",models.toString()); builder.environment().put("OLLAMA_NO_CLOUD","1");
            builder.environment().put("OLLAMA_NUM_PARALLEL","1"); builder.environment().put("OLLAMA_DEBUG_LOG_REQUESTS","false");
            process=starter.start(builder); owned.put(config.baseUrl(),process);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.startupTimeoutSeconds());
            while (process.isAlive() && System.nanoTime()<deadline) {
                checkInterrupted();
                try { health(config); return; } catch (RuntimeException e) { checkInterrupted(); }
                Thread.sleep(100);
            }
            throw new IOException("Local LLM service did not become ready");
        } catch (Exception e) {
            if (process!=null) { stop(process); owned.remove(config.baseUrl(),process); }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            checkInterrupted(); throw new IllegalStateException("Could not start local Ollama; inspect runtime setup and port availability");
        }
    }
    private static int port(OllamaConfig config) { return config.baseUrl().getPort()==-1 ? 80 : config.baseUrl().getPort(); }
    private static boolean occupied(OllamaConfig config) {
        try (var socket=new Socket()) { socket.connect(new InetSocketAddress(config.baseUrl().getHost().replace("[","").replace("]",""),port(config)),500); return true; }
        catch (IOException e) { return false; }
    }
    private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("Local operation cancelled"); }
    private Service health(OllamaConfig config) {
        JsonNode version=get(config,"/api/version"), tags=get(config,"/api/tags");
        if (!version.path("version").isTextual() || !supportsLocalSource(version.path("version").asText())
                || !tags.path("models").isArray() || tags.path("models").size()>1000) throw new IllegalStateException("Incompatible local Ollama service");
        var models=new ArrayList<Model>();
        for (var item:tags.path("models")) {
            String name=item.path("name").asText("");
            if (!item.path("name").isTextual() || name.length()>200 || !name.matches("[A-Za-z0-9][A-Za-z0-9._/:-]*")
                    || !item.path("size").isIntegralNumber() || !item.path("size").canConvertToLong() || item.path("size").longValue()<0
                    || !item.path("digest").isTextual() || !item.path("digest").asText().matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid local model inventory");
            String lower=name.toLowerCase(Locale.ROOT);
            boolean local=!item.hasNonNull("remote_model") && !item.hasNonNull("remote_host") && !lower.endsWith(":cloud") && !lower.endsWith("-cloud");
            models.add(new Model(name,item.path("size").longValue(),item.path("digest").asText(),local));
        }
        return new Service(version.path("version").asText(),List.copyOf(models));
    }
    private static boolean supportsLocalSource(String version) {
        // The managed pinned release establishes the minimum protocol reviewed for :local routing.
        if (version.length()>64 || !version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:[-+][A-Za-z0-9.-]+)?")) return false;
        String[] parts=version.split("[.\\-+]",4);
        return Integer.parseInt(parts[0])>0 || Integer.parseInt(parts[1])>=35;
    }
    private JsonNode get(OllamaConfig config,String path) {
        try {
            var request=HttpRequest.newBuilder(config.baseUrl().resolve(path)).timeout(Duration.ofSeconds(2)).GET().build();
            byte[] body=RuntimeDownload.read(http,request,2,ManagedOllama::checkInterrupted,input -> RuntimeDownload.bounded(input,1_000_000));
            return Json.read(engine.utils.Utf8.decode(body),JsonNode.class);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (CancellationException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Local Ollama unavailable or incompatible"); }
    }
    public void requireCachedModel(OllamaConfig config,String model) {
        model=OllamaConfig.localModelId(model);
        String selected=model.contains(":") ? model : model+":latest";
        String identity=model;
        if (health(config).models().stream().noneMatch(item -> (item.name().equals(selected) || item.name().equals(identity)) && item.local()))
            throw new IllegalStateException("Download the selected local model explicitly before generation");
    }
    public void download(OllamaConfig config,String model,JobService.Context job) throws Exception {
        model=OllamaConfig.localModelId(model); ensureRunning(config); job.checkCancelled();
        final String identity=model;
        String selected=model.contains(":") ? model : model+":latest";
        if (health(config).models().stream().anyMatch(item -> (item.name().equals(identity) || item.name().equals(selected)) && !item.local()))
            throw new IllegalArgumentException("Selected model is a remote alias; choose a local model");
        try { requireCachedModel(config,model); job.update("Selected local model is already cached",readiness(config)); return; }
        catch (IllegalStateException ignored) { }
        job.update("Downloading selected local model; size depends on the configured model",Map.of("model",model));
        var request=HttpRequest.newBuilder(config.baseUrl().resolve("/api/pull"))
                .timeout(Duration.ofSeconds(config.downloadTimeoutSeconds())).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("model",OllamaConfig.localModelReference(model),"stream",true)),StandardCharsets.UTF_8)).build();
        RuntimeDownload.read(http,request,config.downloadTimeoutSeconds(),job::checkCancelled,input -> {
            boolean success=false; long last=0; int records=0;
            // Bound each NDJSON record; buffered readers can otherwise allocate an unbounded line.
            var buffer=new BufferedInputStream(input); byte[] line;
            while ((line=line(buffer))!=null) {
                job.checkCancelled(); if (++records>1_000_000) throw new IOException("Too many model-download progress records");
                var state=Json.read(engine.utils.Utf8.decode(line),JsonNode.class);
                if (state.hasNonNull("error") || !state.path("status").isTextual()) throw new IOException("Model pull failed");
                if (state.path("status").asText().equals("success")) { success=true; break; }
                Map<String,Object> progress=new LinkedHashMap<>(); progress.put("model",identity);
                if (state.has("total") || state.has("completed")) {
                    long completed=state.has("completed") ? state.path("completed").longValue() : 0;
                    if (!state.path("total").isIntegralNumber() || !state.path("total").canConvertToLong() || state.path("total").longValue()<0
                            || (state.has("completed") && (!state.path("completed").isIntegralNumber() || !state.path("completed").canConvertToLong()))
                            || completed<0 || completed>state.path("total").longValue()) throw new IOException("Invalid download byte counts");
                    progress.put("totalBytes",state.path("total").longValue()); progress.put("completedBytes",completed);
                }
                if (System.nanoTime()-last>TimeUnit.MILLISECONDS.toNanos(500)) {
                    job.update("Receiving local model files",progress); last=System.nanoTime();
                }
            }
            if (!success) throw new IOException("Model pull ended without success"); return true;
        });
        job.checkCancelled(); requireCachedModel(config,model);
        job.update("Local model cached; availability and behaviour still require explicit verification",readiness(config));
    }
    private static byte[] line(InputStream input) throws IOException {
        var buffer=new ByteArrayOutputStream(); int value;
        while ((value=input.read())!=-1 && value!='\n') { if (buffer.size()>=65536) throw new IOException("Oversized download progress record"); buffer.write(value); }
        return value==-1 && buffer.size()==0 ? null : buffer.toByteArray();
    }
    private static void stop(Process process) {
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
    }
    @Override public synchronized void close() { closed=true; owned.values().forEach(ManagedOllama::stop); owned.clear(); }
}
