package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.evaluation.local.*;
import engine.transcript.*;
import engine.utils.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in isolated managed installation. Never JUnit, weight downloads or research validation. */
public final class OfficialNlpSetupSmoke {
    private static final String UV_SHA256 = "f730454bf09019754e5e5abd71a8aa18683cb739cba0d9c720bac2e7c901160f";
    private static final List<String> METHODS=List.of("vader-sentiment","cardiff-sentiment","deberta-stance");
    private static final Map<String,String> PINNED_WEIGHTS=Map.of(
            "models--cardiffnlp--twitter-roberta-base-sentiment-latest/snapshots/3216a57f2a0d9c45a2e6c20157c20c49fb4bf9c7/pytorch_model.bin",
            "4d24a3e32a88ed1c4e5b789fc6644e2e767500554e954b27dccf52a8e762cbae",
            "models--MoritzLaurer--deberta-v3-base-zeroshot-v2.0-c/snapshots/bddf8c5411c34ac3565e16e04384fd68b2618dda/model.safetensors",
            "605e40d2020f4c66666db3e98a4bc277b6214565eac95f35c1a5027a93ee0ef0");
    private OfficialNlpSetupSmoke() { }
    public static void main(String[] args) throws Exception {
        if ((args.length != 4 && args.length != 5) || !Set.of("fresh","resume").contains(args[3])
                || args.length==5 && !Set.of("base","models").contains(args[4])) throw new IllegalArgumentException("Use the isolated Windows setup runner");
        boolean models=args.length==5 && args[4].equals("models");
        Path root=Path.of(args[0]).toRealPath(), target=Path.of("target").toRealPath(), stop=Path.of(args[2]).toAbsolutePath().normalize();
        String profile=System.getenv("USERPROFILE"); boolean resumed=args[3].equals("resume");
        require(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && root.startsWith(target) && !root.equals(target)
                && profile!=null && Path.of(profile).toAbsolutePath().normalize().equals(root.resolve("profile")) && root.equals(stop.getParent()), "Use an isolated target fixture/profile");
        require(!models || "1".equals(System.getenv("HF_HUB_OFFLINE")) && "1".equals(System.getenv("TRANSFORMERS_OFFLINE")),"Model checks must reuse cached weights offline");
        int port=Integer.parseInt(args[1]); require(port>=1024 && port<=65535 && port!=8765 && !occupied(port), "Use a free non-default test port");
        Path archive=Path.of(".runtime/uv-0.12.16.zip"); String archiveHash=Hashes.sha256(Files.readAllBytes(archive));
        require(UV_SHA256.equals(archiveHash), "Only the already verified pinned uv archive may be reused");
        if (!resumed) {
            require(!Files.exists(root.resolve("config")) && !Files.exists(root.resolve("nlp")) && !Files.exists(root.resolve(".runtime"))
                    && !Files.exists(root.resolve("runs")), "Fresh setup fixture must have no application or optional runtime files");
            TestFixtures.copyResources(root);
            for (String name:List.of("nlp/pyproject.toml","nlp/uv.lock","scripts/setup-nlp.ps1","scripts/nlp-runtime.ps1")) copy(Path.of(name),root.resolve(name));
            try (var files=Files.walk(Path.of("nlp/src"))) {
                for (Path file:files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".py")).toList()) copy(file,root.resolve(file));
            }
            Files.createDirectories(root.resolve(".runtime")); Files.copy(archive,root.resolve(".runtime/uv-0.12.16.zip"));
            AtomicFiles.write(root.resolve("initial-state.json"),Json.write(Map.of("managedPythonAbsent",true,"environmentAbsent",true,"weightsAbsent",true,"cachedUvArchive",true)));
        }
        Map<String,String> modelFiles=models ? prepareCachedModels(root,resumed) : Map.of();
        require(Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock"))).equals(Hashes.sha256(Files.readAllBytes(Path.of("nlp/uv.lock")))), "Fixture must use the current lockfile");
        Map<String,String> sources=new TreeMap<>();
        try (var files=Files.walk(root.resolve("nlp/src"))) {
            for (Path file:files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".py")).toList()) {
                String relative=root.relativize(file).toString(); String hash=Hashes.sha256(Files.readAllBytes(file));
                require(hash.equals(Hashes.sha256(Files.readAllBytes(Path.of(relative)))), "Fixture Python source must match the current implementation");
                sources.put(relative,hash);
            }
        }
        for (String name:List.of("nlp/pyproject.toml","nlp/uv.lock","nlp/config/models.json","scripts/setup-nlp.ps1","scripts/nlp-runtime.ps1"))
            sources.put(name,Hashes.sha256(Files.readAllBytes(root.resolve(name))));
        AtomicFiles.write(root.resolve("source-identities.json"),Json.write(sources));
        var config=new LocalEvaluationConfig(1,URI.create("http://127.0.0.1:"+port+"/v1/analyze"),300,10,List.of("vader-sentiment"));
        AtomicFiles.write(root.resolve("config/local-evaluation.json"),Json.write(config));
        var app=application(root); Thread cleanup=new Thread(app::close,"base-nlp-acceptance-cleanup"); Runtime.getRuntime().addShutdownHook(cleanup);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("state","FAILED"); result.put("resumedFixture",resumed); result.put("cachedUvArchive",true);
        result.put("mode",models ? "models" : "base"); result.put("copiedPinnedCache",models);
        result.put("researchValidity","not assessed; synthetic functionality inputs only");
        try {
            var before=app.localReadiness(); AtomicFiles.write(root.resolve("readiness-before.json"),Json.write(before));
            if (!resumed) require(!Boolean.TRUE.equals(before.get("installed")) && !Boolean.TRUE.equals(before.get("running")), "Fresh optional installation must start absent");
            System.out.println("Installing current locked lightweight NLP dependencies through the shared explicit job");
            var setup=OfficialOllamaSmoke.finish(app,app.setupLocalNlp().id(),stop); AtomicFiles.write(root.resolve("setup-job.json"),Json.write(setup));
            retainSetupLog(root,setup.id());
            require(setup.state()==BackgroundJob.State.COMPLETE,"Managed base setup failed; retain the fixture/log/cache for explicit resume");
            String lockHash=Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock")));
            require(Files.readString(root.resolve(".runtime/nlp/setup.complete")).equals(lockHash),"Setup marker must bind the current locked environment");
            result.put("lockSha256",lockHash); var ready=app.localReadiness(); requireReadiness(ready,false,models && Files.isRegularFile(root.resolve(".runtime/nlp/models.complete")),models);
            AtomicFiles.write(root.resolve("readiness-installed.json"),Json.write(ready));
            if (models) {
                for (String method:METHODS.subList(1,3)) {
                    System.out.println("Installing current locked CPU extras and checking copied offline cache for "+method);
                    var modelSetup=OfficialOllamaSmoke.finish(app,app.setupLocalModel(method).id(),stop);
                    AtomicFiles.write(root.resolve(method+"-setup-job.json"),Json.write(modelSetup));
                    retainSetupLog(root,modelSetup.id()); result.put(method+"SetupJobId",modelSetup.id());
                    require(modelSetup.state()==BackgroundJob.State.COMPLETE,"Managed model setup failed; retain partial installation for explicit resume");
                    requireReadiness(app.localReadiness(),false,true,true);
                }
                require(Files.readString(root.resolve(".runtime/nlp/models.complete")).equals(lockHash),"Model marker must bind the current locked environment");
                AtomicFiles.write(root.resolve("readiness-models-installed.json"),Json.write(app.localReadiness()));
            }
            var identity=new ProcessBuilder(root.resolve(".runtime/nlp-env/Scripts/python.exe").toString(),"-c",
                    "import json,sys,importlib.metadata as m; print(json.dumps({'python':sys.version.split()[0],'nlp':m.version('parliament-nlp'),'vader':m.version('vaderSentiment'),'fastapi':m.version('fastapi'),'uvicorn':m.version('uvicorn')}))")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!identity.waitFor(15,TimeUnit.SECONDS)) { identity.destroyForcibly(); throw new IllegalStateException("Package identity check timed out"); }
            require(identity.exitValue()==0,"Package identity check failed"); var packages=Json.read(Utf8.decode(identity.getInputStream().readAllBytes()),Map.class);
            require("0.3.0".equals(packages.get("nlp")) && "3.3.2".equals(packages.get("vader")),"Current managed package versions must match"); result.put("packages",packages);
            if (models) result.put("modelPackages",modelPackages(root));
            var member=new Participant("synthetic","Synthetic Unicode member",Party.LABOUR,"Synthetic fixture");
            var topics=new ArrayList<Topic>(); topics.add(new Topic("synthetic-topic","Synthetic functionality input",models ? "New Zealand should build more public housing." : null));
            var events=new ArrayList<PublicEvent>(); events.add(new PublicEvent("synthetic-turn","synthetic-topic",PublicEvent.Type.SPEECH,member,"Whānau 🏠 deserve good housing. Tēnā koutou; cafe\u0301 is lovely. Synthetic input with no gold labels."));
            if (models) {
                topics.add(new Topic("missing-target","Synthetic missing-target boundary",null));
                events.add(new PublicEvent("missing-target-turn","missing-target",PublicEvent.Type.SPEECH,member,"Synthetic boundary: whānau 🏠 need safe homes. No gold labels are supplied."));
            }
            var source=new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(member),topics,events,Transcript.Outcome.COMPLETE);
            var sitting=app.importTranscript(source); result.put("runId",sitting.id());
            AtomicFiles.write(root.resolve("public-evidence.json"),Json.write(sitting.transcript()));
            var analysis=OfficialOllamaSmoke.finish(app,app.evaluate(sitting.id(),Map.of("methods",METHODS)).id(),stop);
            var report=Json.read(Json.write(analysis.result()),LocalReport.class); AtomicFiles.write(root.resolve("analysis-report.json"),Json.write(report));
            Path savedJob=root.resolve("runs/jobs/"+analysis.id()+"/job.json"); String savedJobHash=Hashes.sha256(Files.readAllBytes(savedJob));
            result.put("analysisJobId",analysis.id()); require(analysis.state()==(models ? BackgroundJob.State.COMPLETE : BackgroundJob.State.FAILED) && report.methods().size()==3,"Selected methods must retain explicit separate results");
            var vader=report.methods().stream().filter(method -> method.methodId().equals("vader-sentiment")).findFirst().orElseThrow();
            require(vader.status().equals("ok") && vader.batches().size()==1 && vader.batches().get(0).items().size()==(models ? 2 : 1),"Actual VADER output must cover each Unicode input");
            var provenance=vader.batches().get(0).provenance();
            require(NlpResponse.IMPLEMENTATION_VERSION.equals(provenance.implementationVersion())
                    && Hashes.sha256(Files.readAllBytes(root.resolve("nlp/config/models.json"))).equals(provenance.configSha256()),"Actual output must retain current captured provenance");
            if (models) result.put("separateMethods",requireModelReport(root,report));
            else require(report.methods().stream().filter(method -> !method.methodId().equals("vader-sentiment")).allMatch(method -> method.status().equals("failed")
                    && method.batches().size()==1 && method.batches().stream().allMatch(batch -> batch.status().equals("failed")
                    && "model_unavailable".equals(batch.error()) && batch.items().isEmpty())),"Missing transformers must retain explicit failure metadata without fabricated items");
            requireReadiness(app.localReadiness(),true,models,models);
            AtomicFiles.write(root.resolve("readiness-after-analysis.json"),Json.write(app.localReadiness()));
            System.out.println("Repeating explicit base setup with its managed cache and retained report");
            var repeated=OfficialOllamaSmoke.finish(app,app.setupLocalNlp().id(),stop); retainSetupLog(root,repeated.id());
            result.put("repeatedSetupJobId",repeated.id()); require(repeated.state()==BackgroundJob.State.COMPLETE,"Repeated base setup must reuse its environment");
            requireReadiness(app.localReadiness(),false,models,models);
            AtomicFiles.write(root.resolve("readiness-after-repeat.json"),Json.write(app.localReadiness()));
            if (models) require(Files.readString(root.resolve(".runtime/nlp/models.complete")).equals(lockHash)
                    && result.get("modelPackages").equals(modelPackages(root)),"Repeated base setup must preserve CPU extras and model marker");
            require(savedJobHash.equals(Hashes.sha256(Files.readAllBytes(savedJob)))
                    && Json.parse(Json.write(report)).equals(Json.parse(Json.write(app.background().find(analysis.id()).result()))),"Repeat setup must preserve committed analysis");
            app.close(); awaitReleased(port);
            try (var reopened=application(root)) {
                require(reopened.background().find(analysis.id()).result().equals(Json.parse(Json.write(report))),"Report must reopen without automatic inference");
                require(sitting.transcript().equals(reopened.find(sitting.id()).transcript()),"Public evidence must reopen unchanged");
                require(!Boolean.TRUE.equals(reopened.localReadiness().get("running")),"Recovery must not start inference automatically");
                var restarted=OfficialOllamaSmoke.finish(reopened,reopened.evaluate(sitting.id(),Map.of("methods",models ? METHODS : List.of("vader-sentiment"))).id(),stop);
                require(restarted.state()==BackgroundJob.State.COMPLETE,"Explicit analysis must start the managed service on demand");
                if (models) requireModelReport(root,Json.read(Json.write(restarted.result()),LocalReport.class));
                result.put("restartedAnalysisJobId",restarted.id()); requireReadiness(reopened.localReadiness(),true,models,models);
            }
            for (var entry:sources.entrySet()) require(entry.getValue().equals(Hashes.sha256(Files.readAllBytes(root.resolve(entry.getKey())))),"Captured source/settings must stay unchanged");
            require(archiveHash.equals(Hashes.sha256(Files.readAllBytes(archive))),"Owner uv archive must stay unchanged");
            if (models) for (var entry:modelFiles.entrySet()) require(entry.getValue().equals(fileHash(Path.of("nlp/.models").resolve(entry.getKey())))
                    && entry.getValue().equals(fileHash(root.resolve("nlp/.models").resolve(entry.getKey()))),"Owner and copied model files must stay unchanged");
            else require(!Files.exists(root.resolve(".runtime/nlp/models.complete")) && !Files.exists(root.resolve("nlp/.models")),"Base setup must not install transformer extras or weights");
            result.put("currentInstallation",true); result.put("repeatedSetup",true); result.put("reportRecovery",true); result.put("explicitRestart",true);
            result.put("transformerDependenciesAndWeightsAbsent",!models); result.put("sourceAndArchiveUnchanged",true); result.put("state","PASS");
        } finally {
            app.close(); Runtime.getRuntime().removeShutdownHook(cleanup); awaitReleased(port);
            result.put("ownedPortReleased",!occupied(port)); if (!Boolean.TRUE.equals(result.get("ownedPortReleased"))) result.put("state","FAILED");
            result.put("verifiedAt",java.time.Instant.now().toString()); AtomicFiles.write(root.resolve("result.json"),Json.write(result));
        }
        require(Boolean.TRUE.equals(result.get("ownedPortReleased")),"Owned NLP service must stop");
        System.out.println("Current managed "+(models ? "CPU models" : "base")+" setup/cache/provenance/recovery PASS; no research accuracy claim");
    }
    private static DebateApplication application(Path root) { return new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> { throw new AssertionError("No debate model generation in NLP setup acceptance"); }); }
    private static void requireReadiness(Map<String,Object> ready,boolean loaded,boolean modelDependencies,boolean modelCache) {
        require(Boolean.TRUE.equals(ready.get("installed")) && Boolean.TRUE.equals(ready.get("running")),"Current managed service must respond");
        var service=(Map<?,?>)ready.get("service"); var states=(Map<?,?>)service.get("readiness");
        for (String method:METHODS) {
            var state=(Map<?,?>)states.get(method); boolean base=method.equals("vader-sentiment");
            require(Boolean.valueOf(base || modelDependencies).equals(state.get("dependenciesPresent")) && Boolean.valueOf(base || modelCache).equals(state.get("filesCached"))
                    && Boolean.valueOf((base || modelDependencies) && loaded).equals(state.get("loaded")),"Readiness must distinguish packages, cache and explicit model loading");
        }
    }
    private static Map<String,String> prepareCachedModels(Path root,boolean resumed) throws Exception {
        Path owner=Path.of("nlp/.models"), destination=root.resolve("nlp/.models"); var hashes=new TreeMap<String,String>();
        for (var entry:PINNED_WEIGHTS.entrySet()) {
            require(entry.getValue().equals(fileHash(owner.resolve(entry.getKey()))),"Only existing checksum-pinned weights may be copied");
            Path snapshot=owner.resolve(entry.getKey()).getParent();
            try (var files=Files.walk(snapshot)) {
                for (Path file:files.filter(Files::isRegularFile).toList()) {
                    String relative=owner.relativize(file).toString(); String hash=fileHash(file); hashes.put(relative,hash);
                    if (!resumed) copy(file,destination.resolve(relative));
                    require(hash.equals(fileHash(destination.resolve(relative))),"Copied pinned cache must remain exact");
                }
            }
        }
        Path identity=root.resolve("model-identities.json");
        if (resumed) require(Json.parse(Files.readString(identity)).equals(hashes),"Resume must retain the original copied cache identities");
        else AtomicFiles.write(identity,Json.write(hashes));
        return hashes;
    }
    private static Map<String,Object> modelPackages(Path root) throws Exception {
        var process=new ProcessBuilder(root.resolve(".runtime/nlp-env/Scripts/python.exe").toString(),"-c",
                "import json,importlib.metadata as m; print(json.dumps({n:m.version(n) for n in ('torch','transformers','tokenizers','sentencepiece','protobuf')}))")
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("CPU package identity check timed out"); }
        require(process.exitValue()==0,"CPU package identity check failed");
        var versions=Json.read(Utf8.decode(process.getInputStream().readAllBytes()),Map.class);
        require("2.10.0+cpu".equals(versions.get("torch")) && "4.57.6".equals(versions.get("transformers")),"CPU extras must match the current lock");
        return versions;
    }
    private static Map<String,Object> requireModelReport(Path root,LocalReport report) throws Exception {
        String configHash=fileHash(root.resolve("nlp/config/models.json")); var counts=new LinkedHashMap<String,Object>();
        require(report.methods().size()==3,"All three selected methods must stay separate");
        for (var method:report.methods()) {
            require(method.status().equals("ok"),"Actual selected model output must pass shared validation");
            var items=method.batches().stream().flatMap(batch -> batch.items().stream()).toList();
            require(items.size()==2,"Actual output must cover both synthetic Unicode items");
            var boundary=items.stream().filter(item -> item.turnId().equals("missing-target-turn")).findFirst().orElseThrow();
            if (method.methodId().equals("deberta-stance")) require(boundary.status().equals("insufficient_evidence")
                    && "no_policy_target".equals(boundary.error()) && boundary.chunks().isEmpty(),"Missing public target must remain insufficient evidence");
            else require(boundary.status().equals("ok") && !boundary.chunks().isEmpty(),"Sentiment must retain Unicode boundary coverage");
            for (var batch:method.batches()) require(batch.provenance()!=null && NlpResponse.IMPLEMENTATION_VERSION.equals(batch.provenance().implementationVersion())
                    && configHash.equals(batch.provenance().configSha256()),"Each actual method must retain current captured provenance");
            counts.put(method.methodId(),Map.of("items",items.size(),"chunks",items.stream().mapToLong(item -> item.chunks().size()).sum(),
                    "insufficientItems",items.stream().filter(item -> item.status().equals("insufficient_evidence")).count(),
                    "abstainedChunks",items.stream().flatMap(item -> item.chunks().stream()).filter(chunk -> chunk.uncertainty()!=null && chunk.uncertainty().abstained()).count()));
        }
        return counts;
    }
    private static String fileHash(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (var input=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int read; while ((read=input.read(buffer))!=-1) digest.update(buffer,0,read); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void retainSetupLog(Path root,String jobId) throws Exception { copy(root.resolve(".runtime/nlp/setup.log"),root.resolve("setup-"+jobId+".log")); }
    private static void copy(Path source,Path destination) throws Exception { Files.createDirectories(destination.getParent()); Files.copy(source,destination); }
    private static void awaitReleased(int port) throws Exception { long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10); while (occupied(port) && System.nanoTime()<until) Thread.sleep(50); }
    private static boolean occupied(int port) { try (var socket=new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1",port),200); return true; } catch (java.io.IOException absent) { return false; } }
    private static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }
}
