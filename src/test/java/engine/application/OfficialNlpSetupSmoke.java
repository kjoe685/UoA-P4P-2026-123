package engine.application;

import engine.TestFixtures;
import engine.agent.Party;
import engine.evaluation.local.*;
import engine.transcript.*;
import engine.utils.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in current managed base installation. Never JUnit, model downloads or research validation. */
public final class OfficialNlpSetupSmoke {
    private static final String UV_SHA256 = "f730454bf09019754e5e5abd71a8aa18683cb739cba0d9c720bac2e7c901160f";
    private OfficialNlpSetupSmoke() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("fresh","resume").contains(args[3])) throw new IllegalArgumentException("Use the isolated Windows setup runner");
        Path root=Path.of(args[0]).toRealPath(), target=Path.of("target").toRealPath(), stop=Path.of(args[2]).toAbsolutePath().normalize();
        String profile=System.getenv("USERPROFILE"); boolean resumed=args[3].equals("resume");
        require(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && root.startsWith(target) && !root.equals(target)
                && profile!=null && Path.of(profile).toAbsolutePath().normalize().equals(root.resolve("profile")) && root.equals(stop.getParent()), "Use an isolated target fixture/profile");
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
        var config=new LocalEvaluationConfig(1,URI.create("http://127.0.0.1:"+port+"/v1/analyze"),30,10,List.of("vader-sentiment"));
        AtomicFiles.write(root.resolve("config/local-evaluation.json"),Json.write(config));
        var app=application(root); Thread cleanup=new Thread(app::close,"base-nlp-acceptance-cleanup"); Runtime.getRuntime().addShutdownHook(cleanup);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("state","FAILED"); result.put("resumedFixture",resumed); result.put("cachedUvArchive",true);
        result.put("researchValidity","not assessed; synthetic functionality inputs only");
        try {
            var before=app.localReadiness(); AtomicFiles.write(root.resolve("readiness-before.json"),Json.write(before));
            if (!resumed) require(!Boolean.TRUE.equals(before.get("installed")) && !Boolean.TRUE.equals(before.get("running")), "Fresh optional installation must start absent");
            System.out.println("Installing current locked lightweight NLP dependencies through the shared explicit job");
            var setup=OfficialOllamaSmoke.finish(app,app.setupLocalNlp().id(),stop); AtomicFiles.write(root.resolve("setup-job.json"),Json.write(setup));
            require(setup.state()==BackgroundJob.State.COMPLETE,"Managed base setup failed; retain the fixture/log/cache for explicit resume");
            String lockHash=Hashes.sha256(Files.readAllBytes(root.resolve("nlp/uv.lock")));
            require(Files.readString(root.resolve(".runtime/nlp/setup.complete")).equals(lockHash),"Setup marker must bind the current locked environment");
            result.put("lockSha256",lockHash); var ready=app.localReadiness(); requireReadiness(ready,false);
            AtomicFiles.write(root.resolve("readiness-installed.json"),Json.write(ready));
            var identity=new ProcessBuilder(root.resolve(".runtime/nlp-env/Scripts/python.exe").toString(),"-c",
                    "import json,sys,importlib.metadata as m; print(json.dumps({'python':sys.version.split()[0],'nlp':m.version('parliament-nlp'),'vader':m.version('vaderSentiment'),'fastapi':m.version('fastapi'),'uvicorn':m.version('uvicorn')}))")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!identity.waitFor(15,TimeUnit.SECONDS)) { identity.destroyForcibly(); throw new IllegalStateException("Package identity check timed out"); }
            require(identity.exitValue()==0,"Package identity check failed"); var packages=Json.read(Utf8.decode(identity.getInputStream().readAllBytes()),Map.class);
            require("0.3.0".equals(packages.get("nlp")) && "3.3.2".equals(packages.get("vader")),"Current managed package versions must match"); result.put("packages",packages);
            var member=new Participant("synthetic","Synthetic Unicode member",Party.LABOUR,"Synthetic fixture");
            var source=new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(member),List.of(new Topic("synthetic-topic","Synthetic functionality input",null)),
                    List.of(new PublicEvent("synthetic-turn","synthetic-topic",PublicEvent.Type.SPEECH,member,"Whānau 🏠 deserve good housing. Tēnā koutou; cafe\u0301 is lovely. Synthetic input with no gold labels.")),Transcript.Outcome.COMPLETE);
            var sitting=app.importTranscript(source); result.put("runId",sitting.id());
            var analysis=OfficialOllamaSmoke.finish(app,app.evaluate(sitting.id(),Map.of("methods",List.of("vader-sentiment","cardiff-sentiment","deberta-stance"))).id(),stop);
            var report=Json.read(Json.write(analysis.result()),LocalReport.class); AtomicFiles.write(root.resolve("analysis-report.json"),Json.write(report));
            Path savedJob=root.resolve("runs/jobs/"+analysis.id()+"/job.json"); String savedJobHash=Hashes.sha256(Files.readAllBytes(savedJob));
            result.put("analysisJobId",analysis.id()); require(analysis.state()==BackgroundJob.State.FAILED && report.methods().size()==3,"Missing optional methods must remain explicit separate failures");
            var vader=report.methods().stream().filter(method -> method.methodId().equals("vader-sentiment")).findFirst().orElseThrow();
            require(vader.status().equals("ok") && vader.batches().size()==1 && vader.batches().get(0).items().size()==1,"Actual VADER result must survive missing optional methods");
            var provenance=vader.batches().get(0).provenance();
            require(NlpResponse.IMPLEMENTATION_VERSION.equals(provenance.implementationVersion())
                    && Hashes.sha256(Files.readAllBytes(root.resolve("nlp/config/models.json"))).equals(provenance.configSha256()),"Actual output must retain current captured provenance");
            require(report.methods().stream().filter(method -> !method.methodId().equals("vader-sentiment")).allMatch(method -> method.status().equals("failed")
                    && method.batches().size()==1 && method.batches().stream().allMatch(batch -> batch.status().equals("failed")
                    && "model_unavailable".equals(batch.error()) && batch.items().isEmpty())),"Missing transformers must retain explicit failure metadata without fabricated items");
            requireReadiness(app.localReadiness(),true);
            System.out.println("Repeating explicit base setup with its managed cache and retained report");
            var repeated=OfficialOllamaSmoke.finish(app,app.setupLocalNlp().id(),stop); require(repeated.state()==BackgroundJob.State.COMPLETE,"Repeated base setup must reuse its environment");
            requireReadiness(app.localReadiness(),false);
            require(savedJobHash.equals(Hashes.sha256(Files.readAllBytes(savedJob)))
                    && Json.parse(Json.write(report)).equals(Json.parse(Json.write(app.background().find(analysis.id()).result()))),"Repeat setup must preserve committed analysis");
            app.close(); awaitReleased(port);
            try (var reopened=application(root)) {
                require(reopened.background().find(analysis.id()).result().equals(Json.parse(Json.write(report))),"Report must reopen without automatic inference");
                require(!Boolean.TRUE.equals(reopened.localReadiness().get("running")),"Recovery must not start inference automatically");
                var restarted=OfficialOllamaSmoke.finish(reopened,reopened.evaluate(sitting.id(),Map.of("methods",List.of("vader-sentiment"))).id(),stop);
                require(restarted.state()==BackgroundJob.State.COMPLETE,"Explicit analysis must start the managed service on demand");
                result.put("restartedAnalysisJobId",restarted.id()); requireReadiness(reopened.localReadiness(),true);
            }
            for (var entry:sources.entrySet()) require(entry.getValue().equals(Hashes.sha256(Files.readAllBytes(root.resolve(entry.getKey())))),"Captured source/settings must stay unchanged");
            require(archiveHash.equals(Hashes.sha256(Files.readAllBytes(archive))),"Owner uv archive must stay unchanged");
            require(!Files.exists(root.resolve(".runtime/nlp/models.complete")) && !Files.exists(root.resolve("nlp/.models")),"Base setup must not install transformer extras or weights");
            result.put("currentInstallation",true); result.put("repeatedSetup",true); result.put("reportRecovery",true); result.put("explicitRestart",true);
            result.put("transformerDependenciesAndWeightsAbsent",true); result.put("sourceAndArchiveUnchanged",true); result.put("state","PASS");
        } finally {
            app.close(); Runtime.getRuntime().removeShutdownHook(cleanup); awaitReleased(port);
            result.put("ownedPortReleased",!occupied(port)); if (!Boolean.TRUE.equals(result.get("ownedPortReleased"))) result.put("state","FAILED");
            result.put("verifiedAt",java.time.Instant.now().toString()); AtomicFiles.write(root.resolve("result.json"),Json.write(result));
        }
        require(Boolean.TRUE.equals(result.get("ownedPortReleased")),"Owned NLP service must stop");
        System.out.println("Current managed base setup/cache/provenance/recovery PASS; no research accuracy claim");
    }
    private static DebateApplication application(Path root) { return new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> { throw new AssertionError("No model generation in base setup acceptance"); }); }
    private static void requireReadiness(Map<String,Object> ready,boolean loaded) {
        require(Boolean.TRUE.equals(ready.get("installed")) && Boolean.TRUE.equals(ready.get("running")),"Current managed service must respond");
        var service=(Map<?,?>)ready.get("service"); var states=(Map<?,?>)service.get("readiness");
        for (String method:List.of("vader-sentiment","cardiff-sentiment","deberta-stance")) {
            var state=(Map<?,?>)states.get(method); boolean base=method.equals("vader-sentiment");
            require(Boolean.valueOf(base).equals(state.get("dependenciesPresent")) && Boolean.valueOf(base).equals(state.get("filesCached"))
                    && Boolean.valueOf(base && loaded).equals(state.get("loaded")),"Readiness must distinguish installed base from absent optional models");
        }
    }
    private static void copy(Path source,Path destination) throws Exception { Files.createDirectories(destination.getParent()); Files.copy(source,destination); }
    private static void awaitReleased(int port) throws Exception { long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10); while (occupied(port) && System.nanoTime()<until) Thread.sleep(50); }
    private static boolean occupied(int port) { try (var socket=new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1",port),200); return true; } catch (java.io.IOException absent) { return false; } }
    private static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }
}
