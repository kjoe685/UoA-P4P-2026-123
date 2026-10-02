package engine.application;

import com.sun.net.httpserver.HttpServer;
import engine.TestFixtures;
import engine.agent.Party;
import engine.evaluation.local.*;
import engine.transcript.*;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTargetBoundaryTest {
    @TempDir Path root;
    private static Transcript evidence(List<String> propositions) {
        var member=new Participant("member","PRIVATE_TARGET_IDENTITY_SENTINEL",Party.LABOUR,"Labour");
        var topics=new ArrayList<Topic>(); var events=new ArrayList<PublicEvent>();
        for (int i=0;i<propositions.size();i++) {
            String id="topic-"+i; topics.add(new Topic(id,"Synthetic policy target "+i,propositions.get(i)));
            events.add(new PublicEvent("turn-"+i,id,PublicEvent.Type.SPEECH,member,"Synthetic contribution "+i+"."));
        }
        return new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(member),topics,events,Transcript.Outcome.COMPLETE);
    }
    @Test void blankOptionalTargetsUsePythonWhitespaceRulesWithoutRewritingPublicPropositions() throws Exception {
        TestFixtures.copyResources(root);
        var values=new ArrayList<String>(Arrays.asList(null,""," \t\r\n","\u0085\u00A0\u2007\u202F"));
        for (int cp:new int[]{0x1c,0x1d,0x1e,0x1f,0x1680,0x2000,0x200a,0x2028,0x2029,0x205f,0x3000}) values.add(new String(Character.toChars(cp)));
        int blank=values.size(); values.add("  Build Māori housing 😀 e\u0301.\u00A0"); values.add("\u200B");
        var source=evidence(values); String original=Json.write(source);
        var config=new LocalEvaluationService(root,null,ignored -> null).configuration();
        var stance=NlpInputMapper.batches(source,"deberta-stance",config).get(0);
        for (int i=0;i<blank;i++) assertTrue(stance.turns().get(i).targets().isEmpty(),"Blank target at item "+i);
        for (int i=blank;i<values.size();i++) assertEquals(List.of(new NlpRequest.Target("topic-"+i,values.get(i))),stance.turns().get(i).targets());
        assertEquals(original,Json.write(source));
        for (String method:List.of("vader-sentiment","cardiff-sentiment"))
            assertTrue(NlpInputMapper.batches(source,method,config).get(0).turns().stream().allMatch(turn -> turn.targets().isEmpty()));
    }
    private static NlpResponse mixedResponse(NlpRequest request,String settings) {
        var targeted=request.turns().stream().filter(turn -> !turn.targets().isEmpty()).toList();
        var normal=TransformerDecisionTest.response(new NlpRequest(2,request.methods(),targeted),settings,Map.of("support",.9,"oppose",.1,"unrelated",0d),false).methods().get(0);
        var valid=new HashMap<String,NlpResponse.Item>(); normal.items().forEach(item -> valid.put(item.turnId(),item));
        var items=request.turns().stream().map(turn -> valid.getOrDefault(turn.turnId(),
                new NlpResponse.Item(turn.turnId(),null,"insufficient_evidence","no_policy_target",List.of(),0))).toList();
        return new NlpResponse(2,List.of(new NlpResponse.Method("deberta-stance","ok",null,normal.provenance(),items,0)));
    }
    @Test void mixedHttpBatchRetainsValidAndMissingTargetResultsAcrossApiAndRestart() throws Exception {
        TestFixtures.copyResources(root); String settings=Files.readString(root.resolve("nlp/config/models.json"));
        var requests=new CopyOnWriteArrayList<NlpRequest>(); var rejected=new AtomicInteger();
        var nlp=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        nlp.createContext("/v1/analyze",exchange -> {
            try (exchange) {
                var request=Json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),NlpRequest.class); requests.add(request);
                boolean blank=request.turns().stream().flatMap(turn -> turn.targets().stream()).anyMatch(target -> target.proposition().matches("(?U)\\s*"));
                byte[] body=(blank ? "{\"error\":\"INVALID_OPTIONAL_TARGET_SENTINEL\"}" : Json.write(mixedResponse(request,settings))).getBytes(StandardCharsets.UTF_8);
                if (blank) rejected.incrementAndGet(); exchange.sendResponseHeaders(blank ? 422 : 200,body.length); exchange.getResponseBody().write(body);
            }
        }); nlp.start(); String id,jobId; Transcript captured; Object report;
        try {
            try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Import/evaluation does not generate speeches"); return null; },
                    config -> new HttpNlpClient(URI.create("http://127.0.0.1:"+nlp.getAddress().getPort()+"/v1/analyze"),Duration.ofSeconds(5)))) {
                var imported=app.importTranscript(evidence(Arrays.asList("Build Māori housing 😀."," \t\r\n","\u0085\u00A0",null))); id=imported.id(); captured=imported.transcript();
                var server=WebServer.start(0,app);
                try {
                    var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/debates/"+id+"/evaluate"))
                            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"methods\":[\"deberta-stance\"]}")).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(202,response.statusCode()); jobId=(String)((Map<?,?>)Json.parse(response.body())).get("id");
                    var job=LocalEvaluationTest.finish(app.background(),jobId); assertEquals(BackgroundJob.State.COMPLETE,job.state()); assertEquals(0,rejected.get());
                    var method=((LocalReport)job.result()).methods().get(0); assertEquals("ok",method.status());
                    var items=method.batches().get(0).items(); assertEquals(4,items.size()); assertEquals("support",items.get(0).chunks().get(0).label());
                    for (var item:items.subList(1,items.size())) { assertEquals("insufficient_evidence",item.status()); assertEquals("no_policy_target",item.error()); assertTrue(item.chunks().isEmpty()); }
                    assertEquals(captured,app.find(id).transcript()); assertFalse(Json.write(requests).contains("PRIVATE_TARGET_IDENTITY_SENTINEL"));
                    assertFalse(Json.write(job).contains("INVALID_OPTIONAL_TARGET_SENTINEL")); report=Json.parse(Json.write(job.result()));
                } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
            }
            try (var restarted=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Restart must not generate"); return null; },config -> { fail("Restart must not analyze"); return null; })) {
                assertEquals(captured,restarted.find(id).transcript()); assertEquals(report,restarted.background().find(jobId).result());
                assertEquals(BackgroundJob.State.COMPLETE,restarted.background().find(jobId).state()); assertEquals(1,requests.size());
            }
        } finally { nlp.stop(0); }
    }
}
