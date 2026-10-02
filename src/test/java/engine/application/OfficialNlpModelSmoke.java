package engine.application;

import engine.TestFixtures;
import engine.evaluation.local.*;
import engine.transcript.*;
import engine.utils.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Deliberate offline model execution with existing dependencies; never JUnit or research validation. */
public final class OfficialNlpModelSmoke {
    private OfficialNlpModelSmoke() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Use a new target fixture, free test port and retained public transcript");
        Path project = Path.of("").toAbsolutePath().normalize(), target = project.resolve("target");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path source = Path.of(args[2]).toAbsolutePath().normalize();
        int port = Integer.parseInt(args[1]);
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") || !root.startsWith(target)
                || root.equals(target) || Files.exists(root) || !source.startsWith(target) || !Files.isRegularFile(source)
                || port < 1024 || port > 65535 || port == 8765 || occupied(port))
            throw new IllegalArgumentException("Use a new isolated Windows target fixture and a free non-default port");
        Path python = project.resolve(".runtime/nlp-env/Scripts/python.exe");
        require(Files.isRegularFile(python), "Explicitly install local model dependencies before this offline check");
        TestFixtures.copyResources(root); copyTree(project.resolve("nlp/src"), root.resolve("nlp/src"));
        Files.copy(project.resolve("nlp/uv.lock"),root.resolve("nlp/uv.lock"));
        var identities = new LinkedHashMap<String,Object>();
        Path cached = project.resolve("nlp/.models"), fixtureCache = root.resolve("nlp/.models");
        Map<String,String> ownerModelHashes = new LinkedHashMap<>();
        for (var entry : Map.of(
                "models--cardiffnlp--twitter-roberta-base-sentiment-latest/snapshots/3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7/pytorch_model.bin",
                "4d24a3e32a88ed1c4e5b789fc6644e2e767500554e954b27dccf52a8e762cbae",
                "models--MoritzLaurer--deberta-v3-base-zeroshot-v2.0-c/snapshots/bddf8c5411c34ac3565e16e04384fd68b2618dda/model.safetensors",
                "605e40d2020f4c66666db3e98a4bc277b6214565eac95f35c1a5027a93ee0ef0").entrySet()) {
            require(Files.isRegularFile(cached.resolve(entry.getKey())), "Pinned weights must already be cached; this check never downloads");
            require(fileHash(cached.resolve(entry.getKey())).equals(entry.getValue()), "Existing pinned model weight identity changed");
            ownerModelHashes.put(entry.getKey(),entry.getValue());
        }
        System.out.println("Copying verified pinned model cache into the new offline fixture");
        copyTree(cached,fixtureCache);
        for (var entry : ownerModelHashes.entrySet()) {
            require(fileHash(fixtureCache.resolve(entry.getKey())).equals(entry.getValue()), "Copied model weight identity changed");
            identities.put(entry.getKey(),Map.of("sha256",entry.getValue(),"bytes",Files.size(fixtureCache.resolve(entry.getKey()))));
        }
        identities.put("publicSourceSha256",fileHash(source));
        var sourceHashes=new TreeMap<String,String>();
        try (var files=Files.walk(root.resolve("nlp/src"))) {
            for (var file:files.filter(Files::isRegularFile).toList()) sourceHashes.put(root.relativize(file).toString(),fileHash(file));
        }
        identities.put("pythonSourceHashes",sourceHashes);
        AtomicFiles.write(root.resolve("identities.json"),Json.write(identities));
        Path runtime = root.resolve(".runtime/nlp"); Files.createDirectories(runtime);
        var methods = List.of("vader-sentiment","cardiff-sentiment","deberta-stance");
        var config = new LocalEvaluationConfig(1,URI.create("http://127.0.0.1:"+port+"/v1/analyze"),300,50,methods);
        AtomicFiles.write(root.resolve("config/local-evaluation.json"),Json.write(config));
        var builder = new ProcessBuilder(python.toString(),"-m","parliament_nlp.cli","serve","--config",root.resolve("nlp/config/models.json").toString(),
                "--cache",fixtureCache.toString(),"--port",String.valueOf(port)).directory(root.resolve("nlp").toFile())
                .redirectErrorStream(true).redirectOutput(runtime.resolve("offline-service.log").toFile());
        builder.environment().keySet().removeIf(key -> key.matches("(?i)(OPENAI_API_KEY|ANTHROPIC_API_KEY|GEMINI_API_KEY|XAI_API_KEY|HF_TOKEN|HUGGING_FACE_HUB_TOKEN)"));
        builder.environment().put("PYTHONPATH",root.resolve("nlp/src").toString());
        builder.environment().put("PYTHONNOUSERSITE","1");
        builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
        builder.environment().put("HF_HOME",runtime.resolve("hf-home").toString());
        builder.environment().put("HF_HUB_OFFLINE","1"); builder.environment().put("TRANSFORMERS_OFFLINE","1");
        builder.environment().put("HF_HUB_DISABLE_TELEMETRY","1");
        builder.environment().put("TOKENIZERS_PARALLELISM","false");
        System.out.println("Starting current source with existing Python/dependencies; no setup, downloads or labels");
        var application = new DebateApplication(root,new RunStore(root.resolve("runs")),model -> {
            throw new AssertionError("Local analysis acceptance must never construct debate/cloud providers");
        });
        Process service;
        try { service=builder.start(); }
        catch (Exception error) { application.close(); throw error; }
        Thread cleanup = new Thread(() -> { application.close(); stop(service); },"offline-nlp-acceptance-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        var result = new LinkedHashMap<String,Object>(); result.put("state","FAILED");
        result.put("implementationVersion",NlpResponse.IMPLEMENTATION_VERSION);
        result.put("dependencies","reused existing managed Python environment; no dependency installation");
        result.put("researchValidity","not assessed; no human/gold labels");
        try {
            AtomicFiles.write(root.resolve("process.json"),Json.write(Map.of("pid",service.pid(),"port",port,"startedAt",java.time.Instant.now().toString())));
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30); Map<String,Object> readiness;
            do {
                readiness=application.localReadiness();
                if (Boolean.TRUE.equals(readiness.get("running"))) break;
                require(service.isAlive(),"Offline service stopped; inspect retained fixture log"); Thread.sleep(200);
            } while (System.nanoTime()<deadline);
            require(Boolean.TRUE.equals(readiness.get("running")),"Current offline service did not become ready");
            AtomicFiles.write(root.resolve("readiness-before.json"),Json.write(readiness));
            require(!Boolean.TRUE.equals(readiness.get("installed")),"This fixture does not represent a managed dependency installation");
            requireCachedState(readiness,false);
            Transcript original = Json.read(Files.readString(source),Transcript.class);
            require(original.outcome()==Transcript.Outcome.COMPLETE && original.topics().size()==1
                    && original.events().stream().filter(event -> event.speaker()!=null).count()==2,
                    "Use a retained completed single-topic two-speech public sitting");
            var topics = new ArrayList<>(original.topics());
            topics.set(0,new Topic(topics.get(0).id(),topics.get(0).title(),"New Zealand should build more public housing."));
            topics.add(new Topic("synthetic-boundary","Synthetic Unicode/missing-target boundary fixture",null));
            var events = new ArrayList<>(original.events());
            events.add(new PublicEvent("synthetic-topic","synthetic-boundary",PublicEvent.Type.TOPIC,null,topics.get(1).title()));
            String unicode="Synthetic boundary input: whānau 🏠 need good housing. Tēnā koutou; cafe\u0301 matters. This fixture carries no gold labels.";
            events.add(new PublicEvent("synthetic-unicode","synthetic-boundary",PublicEvent.Type.SPEECH,original.roster().get(0),unicode));
            var evidence = new Transcript(2,original.runId(),original.startedAt(),original.endedAt(),original.roster(),topics,events,original.outcome());
            var sitting = application.importTranscript(evidence);
            result.put("runId",sitting.id());
            AtomicFiles.write(root.resolve("public-evidence.json"),Json.write(sitting.transcript()));
            System.out.println("Analyzing retained real public speeches plus an explicitly synthetic missing-target/Unicode boundary");
            var job = application.evaluate(sitting.id(),Map.of("methods",methods));
            deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10); String previous="";
            while (!application.background().find(job.id()).terminal()) {
                job=application.background().find(job.id());
                if (!job.progress().equals(previous)) { System.out.println(job.progress()); previous=job.progress(); }
                if (System.nanoTime()>deadline || Files.exists(root.resolve("stop.requested"))) {
                    application.background().cancel(job.id()); throw new IllegalStateException("Offline acceptance stopped; partial reports retained");
                }
                Thread.sleep(250);
            }
            job=application.background().find(job.id());
            AtomicFiles.write(root.resolve("analysis-job.json"),Json.write(job));
            var report=Json.read(Json.write(job.result()),LocalReport.class);
            AtomicFiles.write(root.resolve("analysis-report.json"),Json.write(report));
            result.put("jobId",job.id()); result.put("jobState",job.state());
            require(job.state()==BackgroundJob.State.COMPLETE && report.methods().size()==3,"Actual offline output failed shared validation; inspect retained report");
            Map<String,Object> counts=new LinkedHashMap<>();
            for (var method:report.methods()) {
                require(method.status().equals("ok"),"Each actual selected method must retain its separate result");
                var items=method.batches().stream().flatMap(batch -> batch.items().stream()).toList();
                require(items.size()==3,"Actual output must cover all three speech items");
                var boundary=items.stream().filter(item -> item.turnId().equals("synthetic-unicode")).findFirst().orElseThrow();
                if (method.methodId().equals("deberta-stance")) require(boundary.status().equals("insufficient_evidence")
                        && "no_policy_target".equals(boundary.error()) && boundary.chunks().isEmpty(),"Missing target must remain insufficient evidence");
                else require(boundary.status().equals("ok") && !boundary.chunks().isEmpty(),"Sentiment must retain the Unicode boundary item");
                for (var batch:method.batches()) require(batch.provenance()!=null
                        && NlpResponse.IMPLEMENTATION_VERSION.equals(batch.provenance().implementationVersion())
                        && Hashes.sha256(Files.readString(root.resolve("nlp/config/models.json"))).equals(batch.provenance().configSha256()),"Actual provenance must match captured bytes/current source");
                counts.put(method.methodId(),Map.of("items",items.size(),"chunks",items.stream().mapToLong(item -> item.chunks().size()).sum(),
                        "insufficientItems",items.stream().filter(item -> item.status().equals("insufficient_evidence")).count(),
                        "abstainedChunks",items.stream().flatMap(item -> item.chunks().stream()).filter(chunk -> chunk.uncertainty()!=null && chunk.uncertainty().abstained()).count()));
            }
            result.put("separateMethods",counts);
            var after=application.localReadiness(); requireCachedState(after,true);
            AtomicFiles.write(root.resolve("readiness-after.json"),Json.write(after));
            application.close();
            try (var restarted=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { throw new AssertionError("Recovery must not generate"); })) {
                require(Json.parse(Json.write(report)).equals(restarted.background().find(job.id()).result()),"Committed actual report must reopen without analysis");
                require(sitting.transcript().equals(restarted.find(sitting.id()).transcript()),"Committed public evidence must reopen unchanged");
            }
            for (var entry:ownerModelHashes.entrySet()) require(fileHash(cached.resolve(entry.getKey())).equals(entry.getValue()),"Owner cached weights changed");
            require(fileHash(source).equals(identities.get("publicSourceSha256")),"Retained source evidence changed");
            result.put("reportRecovery",true); result.put("ownerCacheAndSourceUnchanged",true); result.put("state","PASS");
        } finally {
            application.close(); stop(service); Runtime.getRuntime().removeShutdownHook(cleanup);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (occupied(port) && System.nanoTime()<deadline) Thread.sleep(50);
            result.put("ownedPortReleased",!occupied(port));
            if (!Boolean.TRUE.equals(result.get("ownedPortReleased"))) result.put("state","FAILED");
            result.put("verifiedAt",java.time.Instant.now().toString()); AtomicFiles.write(root.resolve("result.json"),Json.write(result));
        }
        require(Boolean.TRUE.equals(result.get("ownedPortReleased")),"Offline acceptance must release its owned service");
        System.out.println("Actual current offline model/function/provenance checks PASS; no research accuracy claim");
        System.out.println("Result: "+root.resolve("result.json"));
    }
    private static void requireCachedState(Map<String,Object> readiness,boolean loaded) {
        var service=(Map<?,?>)readiness.get("service"); var methods=(Map<?,?>)service.get("readiness");
        for (String id:List.of("vader-sentiment","cardiff-sentiment","deberta-stance")) {
            var method=(Map<?,?>)methods.get(id);
            require(Boolean.TRUE.equals(method.get("dependenciesPresent")) && Boolean.TRUE.equals(method.get("filesCached"))
                    && Boolean.valueOf(loaded).equals(method.get("loaded")),"Separate package/cache/loaded readiness must reflect real state");
        }
    }
    private static void copyTree(Path source,Path destination) throws Exception {
        try (var paths=Files.walk(source)) {
            for (var file:paths.filter(Files::isRegularFile).filter(file -> !file.toString().contains("__pycache__")).toList()) {
                Path target=destination.resolve(source.relativize(file)); Files.createDirectories(target.getParent()); Files.copy(file,target);
            }
        }
    }
    private static String fileHash(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try (var input=Files.newInputStream(file)) { byte[] bytes=new byte[1024*1024]; for (int count;(count=input.read(bytes))!=-1;) digest.update(bytes,0,count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static boolean occupied(int port) {
        try (var socket=new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1",port),200); return true; }
        catch (java.io.IOException e) { return false; }
    }
    private static void stop(Process process) { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
    private static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }
}
