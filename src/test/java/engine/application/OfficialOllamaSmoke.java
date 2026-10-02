package engine.application;

import engine.TestFixtures;
import engine.config.OllamaConfig;
import engine.utils.AtomicFiles;
import engine.utils.Json;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in real runtime acceptance. Never run by JUnit; downloads no model weights. */
public final class OfficialOllamaSmoke {
    private OfficialOllamaSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Use an isolated fixture root, free loopback port and stop-signal file");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path profile = root.resolve("profile");
        Path stopSignal = Path.of(args[2]).toAbsolutePath().normalize();
        String userProfile = System.getenv("USERPROFILE");
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                || !root.startsWith(target) || root.equals(target) || userProfile == null
                || !Path.of(userProfile).toAbsolutePath().normalize().equals(profile) || !root.equals(stopSignal.getParent()))
            throw new IllegalArgumentException("This Windows acceptance requires a fixture-local child USERPROFILE");
        int port = Integer.parseInt(args[1]);
        if (port < 1024 || port > 65535 || port == 11434 || occupied(port))
            throw new IllegalArgumentException("Choose a free non-default test port");
        Files.createDirectories(profile);
        if (!Files.isDirectory(root.resolve("config"))) TestFixtures.copyResources(root);
        var config = new OllamaConfig(1, URI.create("http://127.0.0.1:" + port), 16384, 60, 3600);
        AtomicFiles.write(root.resolve("config/ollama.json"), Json.write(config));
        var runtime = new ManagedOllama(root);
        var application = new DebateApplication(root, new RunStore(root.resolve("runs")), (model, capturedRuntime) -> {
            throw new AssertionError("This runtime acceptance must not construct a debate model");
        }, null, runtime);
        Thread cleanup = new Thread(application::close, "official-runtime-acceptance-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("state", "FAILED");
        try {
            var before = application.ollamaReadiness();
            require(Boolean.FALSE.equals(before.get("running")), "Fixture port must start without a service");
            result.put("installedBefore", before.get("installed"));
            result.put("runtimePackage", before.get("runtimePackage"));
            BackgroundJob first = finish(application, application.setupOllama().id(), stopSignal);
            AtomicFiles.write(root.resolve("first-setup.json"), Json.write(first));
            require(first.state() == BackgroundJob.State.COMPLETE, "Official runtime setup did not complete; inspect retained fixture progress");
            var ready = application.ollamaReadiness();
            require(Boolean.TRUE.equals(ready.get("installed")) && Boolean.TRUE.equals(ready.get("running"))
                    && Boolean.TRUE.equals(ready.get("owned")), "Official service must be installed, running and owned");
            require(OllamaPackage.VERSION.equals(ready.get("version")), "Official service version must equal its pin");
            require(ready.get("models") instanceof List<?> models && models.isEmpty(), "Acceptance cache must remain empty");
            require(Files.isRegularFile(profile.resolve(".ollama/id_ed25519"))
                    && Files.isRegularFile(profile.resolve(".ollama/id_ed25519.pub")), "Upstream signing keys must stay in the isolated test profile");
            var installer = new OllamaInstaller(root, OllamaPackage.current());
            Path executable = installer.executable();
            long modified = Files.getLastModifiedTime(executable).toMillis();
            String executableHash = OllamaInstaller.sha256(executable);
            BackgroundJob second = finish(application, application.setupOllama().id(), stopSignal);
            AtomicFiles.write(root.resolve("second-setup.json"), Json.write(second));
            require(second.state() == BackgroundJob.State.COMPLETE, "Repeated official setup must complete");
            require(Files.getLastModifiedTime(executable).toMillis() == modified
                    && OllamaInstaller.sha256(executable).equals(executableHash), "Repeated setup must reuse the verified executable");
            Path blobs = root.resolve(".runtime/ollama-models/blobs");
            if (Files.isDirectory(blobs)) {
                try (var files = Files.list(blobs)) { require(files.findAny().isEmpty(), "No model blobs may be downloaded"); }
            }
            result.put("state", "PASS"); result.put("version", ready.get("version"));
            result.put("firstSetupJob", first.id()); result.put("secondSetupJob", second.id());
            result.put("ownedService", true); result.put("emptyModelCache", true);
            result.put("isolatedProfileKeys", true); result.put("verifiedExecutableReused", true);
            result.put("modelGeneration", "not performed"); result.put("loopbackPort", port);
        } finally {
            application.close(); Runtime.getRuntime().removeShutdownHook(cleanup);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (occupied(port) && System.nanoTime() < deadline) Thread.sleep(50);
            result.put("ownedPortReleased", !occupied(port));
            if (!Boolean.TRUE.equals(result.get("ownedPortReleased"))) result.put("state", "FAILED");
            result.put("verifiedAt", java.time.Instant.now().toString());
            AtomicFiles.write(root.resolve("result.json"), Json.write(result));
        }
        require(Boolean.TRUE.equals(result.get("ownedPortReleased")), "Application close must release its owned runtime port");
        System.out.println("Official pinned Windows Ollama runtime/service acceptance PASS; no weights or generation");
        System.out.println("Result: " + root.resolve("result.json"));
    }

    private static BackgroundJob finish(DebateApplication application, String id, Path stopSignal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(65);
        String previous = "";
        for (;;) {
            if (Files.exists(stopSignal)) {
                application.background().cancel(id);
                throw new IllegalStateException("Official acceptance stopped; partial files and progress retained");
            }
            var job = application.background().find(id);
            if (!job.progress().equals(previous)) { System.out.println(job.progress()); previous = job.progress(); }
            if (job.terminal()) return job;
            if (System.nanoTime() > deadline) {
                application.background().cancel(id);
                throw new IllegalStateException("Official acceptance timed out; partial files and progress retained");
            }
            Thread.sleep(250);
        }
    }
    private static boolean occupied(int port) {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 250); return true; }
        catch (java.io.IOException e) { return false; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
