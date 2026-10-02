package engine;

import engine.application.*;
import engine.demo.DemoChatManager;
import engine.transcript.Transcript;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CliParityTest {
    @TempDir Path root;
    @Test void localLlmRuntimeAndModelSetupHaveBrowserCommandAndGuidedParity() throws Exception {
        TestFixtures.copyResources(root);
        try (var fixture=new OllamaFixture(); var manager=fixture.manager(root)) {
            Files.writeString(root.resolve("config/ollama.json"),Json.write(fixture.config()));
            var factory=new engine.provider.ProviderFactory(root,manager);
            try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),factory::forModel,null,manager)) {
                var server=WebServer.start(0,app);
                try {
                    String base="http://localhost:"+server.getAddress().getPort(); var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes); var cli=new Main(base,new Scanner(""),output);
                    cli.command(new String[]{"ollama","status"}); assertTrue(bytes.toString().contains("\"installed\":false")); bytes.reset();
                    var http=java.net.http.HttpClient.newHttpClient();
                    var installRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/ollama-setup")).header("Content-Type","application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
                    var response=http.send(installRequest,java.net.http.HttpResponse.BodyHandlers.ofString()); assertEquals(202,response.statusCode());
                    String installId=(String)((Map<?,?>)Json.parse(response.body())).get("id"); finishJob(app,installId); assertEquals(BackgroundJob.State.COMPLETE,app.background().find(installId).state());
                    cli.command(new String[]{"ollama","download","qwen3-local"}); String modelId=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset(); finishJob(app,modelId);
                    assertEquals(BackgroundJob.State.COMPLETE,app.background().find(modelId).state());
                    new Main(base,new Scanner("18\ndownload\nqwen3-local\n18\nsetup\n0\n"),output).menu();
                    for (var job:app.background().list()) finishJob(app,job.id());
                    assertEquals(4,app.background().list().size()); assertTrue(app.background().list().stream().allMatch(job -> job.state()==BackgroundJob.State.COMPLETE));
                    assertTrue(bytes.toString().contains("qwen3:8b")); assertTrue(bytes.toString().contains("Ollama presets: [qwen3-local]"));
                    var run=app.start(Map.of("topics",List.of("Software QA"),"rounds",1,"groundingCount",0,"agentModelPreset","qwen3-local","members",List.of(Map.of("party","LABOUR"))));
                    cli.command(new String[]{"watch",run.id()}); assertEquals(Transcript.Outcome.COMPLETE,run.transcript().outcome());
                    assertTrue(Json.write(run.transcript()).contains("Synthetic local speech")); assertFalse(Json.write(run.transcript()).contains("runtimePackage"));
                    assertEquals("Choose an Ollama model preset",assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"ollama","download","demo"})).getMessage());
                    var bad=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/ollama-model-setup")).header("Content-Type","application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"modelPreset\":\"qwen3-local\",\"apiKey\":\"SECRET_SENTINEL\"}")).build();
                    var invalid=http.send(bad,java.net.http.HttpResponse.BodyHandlers.ofString()); assertEquals(400,invalid.statusCode()); assertFalse(invalid.body().contains("SENTINEL"));
                    Path report=root.resolve("local-setup-report.json"); cli.command(new String[]{"report",modelId,report.toString()}); assertTrue(Files.readString(report).contains("qwen3:8b"));
                } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
            }
        }
    }
    private static void finishJob(DebateApplication app,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!app.background().find(id).terminal() && System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(app.background().find(id).terminal());
    }
    @Test void browserCommandAndMenuRulingsSurviveTheFinalProviderResponse() throws Exception {
        TestFixtures.copyResources(root);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> {
            entered.countDown();
            try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new engine.chat.ChatResponse("Final public speech.","demo","demo",engine.chat.ChatResponse.CompletionStatus.COMPLETED,null,0);
        })) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                var run=app.start(Map.of("topics",List.of("Housing"),"rounds",1,"members",List.of(Map.of("party","LABOUR"))));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var http=java.net.http.HttpClient.newHttpClient();
                var browserRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/debates/"+run.id()+"/speaker"))
                        .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(Map.of("message","Browser ruling.")))).build();
                assertEquals(202,http.send(browserRequest,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
                var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),output);
                cli.command(new String[]{"ruling",run.id(),"Command ruling."});
                new Main(base,new Scanner("4\n"+run.id()+"\nGuided ruling: ā.\n0\n"),output).menu();
                release.countDown(); cli.command(new String[]{"watch",run.id()});
                assertEquals(Transcript.Outcome.COMPLETE,run.transcript().outcome());
                assertEquals(List.of("Browser ruling.","Command ruling.","Guided ruling: ā."),run.transcript().events().stream()
                        .filter(event -> event.type()==engine.transcript.PublicEvent.Type.CHAIR_RULING).map(engine.transcript.PublicEvent::text).toList());
                Path exported=root.resolve("chair-public.json"); cli.command(new String[]{"transcript",run.id(),exported.toString()});
                assertEquals(run.transcript(),Json.read(Files.readString(exported),Transcript.class));
                assertEquals(409,http.send(browserRequest,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"ruling",run.id(),"Too late."}));
                bytes.reset(); new Main(base,new Scanner("4\n"+run.id()+"\nToo late.\n0\n"),output).menu();
                assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Operation unavailable"));
            } finally { release.countDown(); server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void missingImportFileReturnsToMenuAndAllowsTheNextOperation() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Missing file cannot construct providers"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes);
                var menu=new Main("http://localhost:"+server.getAddress().getPort(),new Scanner("8\n"+root.resolve("missing.json")+"\n16\n\n0\n"),output);
                menu.menu(); assertTrue(bytes.toString().contains("Could not read or write the selected file"));
                assertTrue(bytes.toString().contains("\"totalExcerpts\":500")); assertTrue(app.runs().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void pilotImportsExportsPreparationAndReportsHaveCommandMenuParity() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> null,config -> request -> new engine.evaluation.local.NlpResponse(2,List.of(
                new engine.evaluation.local.NlpResponse.Method(request.methods().get(0),"failed","model_unavailable",null,List.of(),0))))) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes); var cli=new Main(base,new Scanner(""),output);
                Path file=root.resolve("reviewed-fixture.json"); Files.writeString(file,Json.write(PilotTest.fixture("synthetic_fixture",200)));
                cli.command(new String[]{"pilot","import",file.toString()}); String id=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset();
                cli.command(new String[]{"pilot","list"}); assertTrue(bytes.toString().contains(id)); bytes.reset();
                Path exported=root.resolve("exported-pilot.json"); cli.command(new String[]{"pilot","show",id,exported.toString()}); assertEquals(app.pilots().read(id),Json.read(Files.readString(exported),engine.evaluation.local.PilotDataset.class));
                cli.command(new String[]{"pilot","evaluate",id,"vader-sentiment"}); String jobId=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset();
                long until=System.currentTimeMillis()+10000; while (!app.background().find(jobId).terminal() && System.currentTimeMillis()<until) Thread.sleep(10);
                Path report=root.resolve("pilot-report.json"); cli.command(new String[]{"report",jobId,report.toString()}); assertTrue(Files.readString(report).contains("synthetic_fixture"));
                new Main(base,new Scanner("17\nshow\n"+id+"\n\n0\n"),output).menu();
                Files.writeString(file,Json.write(PilotTest.fixture("unreviewed",240))); bytes.reset(); cli.command(new String[]{"pilot","import",file.toString()});
                String candidate=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset(); cli.command(new String[]{"pilot","prepare",candidate,"123"});
                assertTrue(bytes.toString().contains("200")); new Main(base,new Scanner("17\nprepare\n"+candidate+"\n123\n0\n"),output).menu();
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"pilot","evaluate",candidate}));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void corpusCommandsAndMenuValidateBeforeAtomicImportThroughSharedApi() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Corpus operations must not create providers"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes);
                var cli=new Main(base,new Scanner(""),output); Path candidate=root.resolve("candidate.json");
                var corpus=(Map<?,?>)Json.parse(app.assets().read(CorpusService.PATH)); Collections.swap((List<?>)corpus.get("Labour"),0,1);
                Files.writeString(candidate,Json.write(corpus)); String before=(String)app.corpus().status().get("sha256");
                cli.command(new String[]{"corpus","status"}); assertTrue(bytes.toString().contains("500")); bytes.reset();
                cli.command(new String[]{"corpus","validate",candidate.toString()}); assertTrue(bytes.toString().contains("\"saved\":false"));
                assertEquals(before,app.corpus().status().get("sha256")); bytes.reset();
                cli.command(new String[]{"corpus","import",candidate.toString()}); assertTrue(bytes.toString().contains("\"saved\":true"));
                assertNotEquals(before,app.corpus().status().get("sha256"));
                Files.writeString(candidate,Json.write(corpus));
                new Main(base,new Scanner("16\n"+candidate+"\nimport\n0\n"),output).menu();
                assertEquals(Files.readString(candidate),app.assets().read(CorpusService.PATH));
                Files.writeString(candidate,"{}"); assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"corpus","import",candidate.toString()}));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void llmRubricCommandsMenuAndReportsShareTheApplication() throws Exception {
        TestFixtures.copyResources(root); var resources=engine.evaluation.llm.LlmEvaluationResources.load(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> request -> LlmEvaluationTest.fake(request,resources.rubric()))) {
            var run=app.importTranscript(LlmEvaluationTest.evidence()); var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var bytes=new ByteArrayOutputStream();
                var output=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8); var cli=new Main(base,new Scanner(""),output);
                cli.command(new String[]{"evaluate-llm",run.id(),"gpt-4o-mini"});
                String jobId=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id");
                long until=System.currentTimeMillis()+10000; while (!app.background().find(jobId).terminal() && System.currentTimeMillis()<until) Thread.sleep(10);
                assertEquals(BackgroundJob.State.COMPLETE,app.background().find(jobId).state());
                Path report=root.resolve("llm-report.json"); cli.command(new String[]{"report",jobId,report.toString()});
                var result=Json.read(Files.readString(report),engine.evaluation.llm.LlmReport.class);
                assertEquals(5,result.assessments().rubric().metrics().size()); assertEquals("gpt-4o-mini",result.assessments().model().model());
                new Main(base,new Scanner("15\n"+run.id()+"\ngpt-4o-mini\n0\n"),output).menu();
                assertEquals(2,app.background().list().size());
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"evaluate-llm",run.id(),"demo"}));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void commandsAndGuidedMenuUseSameValidationPersistenceAndExportsAsBrowser() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> new DemoChatManager())) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                var output=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),output);
                Path settings=root.resolve("settings.json");
                Files.writeString(settings,Json.write(Map.of("topics",List.of("Housing"),"rounds",1,"members",List.of(Map.of("party","LABOUR")),"agentModelPreset","demo")));
                cli.command(new String[]{"settings","save","baseline",settings.toString()}); bytes.reset();
                cli.command(new String[]{"settings","list"}); assertTrue(bytes.toString().contains("baseline")); bytes.reset();
                cli.command(new String[]{"settings","show","baseline"}); assertTrue(bytes.toString().contains("Housing")); bytes.reset();
                cli.command(new String[]{"assets","list"}); assertTrue(bytes.toString().contains("prompts/BasePrompt.txt")); bytes.reset();
                Path edited=root.resolve("edited.txt"); String basePrompt=Files.readString(root.resolve("prompts/BasePrompt.txt"));
                Files.writeString(edited,basePrompt+"\nCLI_EDIT_SENTINEL");
                cli.command(new String[]{"assets","validate","prompts/BasePrompt.txt",edited.toString()});
                assertEquals(basePrompt,app.assets().read("prompts/BasePrompt.txt"));
                cli.command(new String[]{"assets","save","prompts/BasePrompt.txt",edited.toString()});
                assertTrue(app.assets().read("prompts/BasePrompt.txt").contains("CLI_EDIT_SENTINEL")); bytes.reset();
                cli.command(new String[]{"start",settings.toString()});
                String id=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset();
                cli.command(new String[]{"watch",id});
                assertTrue(bytes.toString().contains("\"outcome\":\"complete\"")); bytes.reset();
                cli.command(new String[]{"runs"}); assertTrue(bytes.toString().contains(id)); bytes.reset();
                Path transcript=root.resolve("public.json"), text=root.resolve("public.txt");
                cli.command(new String[]{"transcript",id,transcript.toString()});
                cli.command(new String[]{"export",id,text.toString()});
                assertEquals(app.find(id).transcript(),Json.read(Files.readString(transcript),Transcript.class));
                assertEquals(app.textExport(id),Files.readString(text));
                assertFalse(Files.readString(transcript).contains("strategy"));
                cli.command(new String[]{"import",transcript.toString()});
                assertEquals(2,app.runs().size());
                String guided="1\n\n\nTransport\n1\nLABOUR\n\n\n\n2\n0\n";
                new Main(base,new Scanner(guided),output).menu();
                assertEquals(3,app.runs().size());
                assertTrue(app.runs().stream().anyMatch(run -> run.topics().get(0).title().equals("Transport")));
                new Main(base,new Scanner("9\nbaseline\n\n10\nprompts/BasePrompt.txt\n\n11\nbaseline\n\n0\n"),output).menu();
                assertEquals(4,app.runs().size());
                Files.writeString(settings,"{\"rounds\":1.5,\"members\":[{\"party\":\"LABOUR\"}]}");
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"start",settings.toString()}));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void evaluationJobsAndReportsUseSharedCommandsAndGuidedMenu() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> new DemoChatManager(),
                config -> request -> new engine.evaluation.local.NlpResponse(2,List.of(new engine.evaluation.local.NlpResponse.Method(
                        request.methods().get(0),"failed","model_unavailable",null,List.of(),0))))) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                var output=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8); var cli=new Main(base,new Scanner(""),output);
                var run=app.start(TestFixtures.settings()); cli.command(new String[]{"watch",run.id()}); bytes.reset();
                cli.command(new String[]{"local","status"}); assertTrue(bytes.toString().contains("installed")); bytes.reset();
                cli.command(new String[]{"evaluate",run.id(),"vader-sentiment"});
                String id=(String)((Map<?,?>)Json.parse(bytes.toString())).get("id"); bytes.reset();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!app.background().find(id).terminal() && System.nanoTime()<deadline) Thread.sleep(10);
                cli.command(new String[]{"jobs"}); assertTrue(bytes.toString().contains(id)); bytes.reset();
                cli.command(new String[]{"job",id}); assertTrue(bytes.toString().contains("model_unavailable")); bytes.reset();
                Path report=root.resolve("analysis.json"); cli.command(new String[]{"report",id,report.toString()});
                assertTrue(Files.readString(report).contains("vader-sentiment"));
                assertFalse(Files.readString(report).contains("strategy"));
                new Main(base,new Scanner("12\n\n13\n"+run.id()+"\n\n14\n"+id+"\nreport\n\n0\n"),output).menu();
                assertEquals(2,app.background().list().size());
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"evaluate",run.id(),"unknown"}));
                cli.command(new String[]{"cancel-job",id}); assertEquals(BackgroundJob.State.FAILED,app.background().find(id).state());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
