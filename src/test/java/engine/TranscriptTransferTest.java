package engine;

import engine.agent.Party;
import engine.application.*;
import engine.transcript.*;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptTransferTest {
    @TempDir Path root;
    private static Transcript largeEvidence() {
        var participant=new Participant("labour","Māori 😀 synthetic member",Party.LABOUR,"Labour");
        var topic=new Topic("topic-1","Large synthetic transfer","Build more public homes");
        String text="Māori 😀 synthetic public evidence. ".repeat(2200);
        var events=new ArrayList<PublicEvent>();
        for (int i=0;i<30;i++) events.add(new PublicEvent("turn-"+(i+1),topic.id(),PublicEvent.Type.SPEECH,participant,"Contribution "+i+": "+text));
        return new Transcript(2,UUID.randomUUID().toString(),1L,2L,List.of(participant),List.of(topic),events,Transcript.Outcome.COMPLETE);
    }
    private static HttpResponse<String> post(HttpClient http,String url,String body,boolean chunked) throws Exception {
        var publisher=chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) : HttpRequest.BodyPublishers.ofString(body);
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(publisher).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void sameEvidence(Transcript expected,Transcript actual) {
        assertNotEquals(expected.runId(),actual.runId()); assertEquals(expected.roster(),actual.roster());
        assertEquals(expected.topics(),actual.topics()); assertEquals(expected.events(),actual.events());
        assertEquals(expected.startedAt(),actual.startedAt()); assertEquals(expected.endedAt(),actual.endedAt()); assertEquals(expected.outcome(),actual.outcome());
    }
    @Test void rawImportPreservesExactLongMetadataAndRefusesDuplicateOrFractionalSchemaTokens() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs"));
        var evidence=largeEvidence(); long exact=9007199254740993L;
        var source=new Transcript(2,evidence.runId(),exact,exact+2,evidence.roster(),evidence.topics(),evidence.events(),Transcript.Outcome.COMPLETE);
        String text=Json.write(source);
        try (var app=new DebateApplication(root,store,model -> { fail("Raw file imports cannot construct providers"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                var http=HttpClient.newHttpClient(); String base="http://localhost:"+server.getAddress().getPort();
                var response=post(http,base+"/api/debates/import",text,false); assertEquals(201,response.statusCode());
                String id=(String)((Map<?,?>)Json.parse(response.body())).get("id"); sameEvidence(source,app.find(id).transcript());
                Path saved=root.resolve("runs/"+id+"/transcript.json"); byte[] original=Files.readAllBytes(saved);
                var exported=http.send(HttpRequest.newBuilder(URI.create(base+"/api/debates/"+id+"/transcript")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,exported.statusCode()); assertTrue(exported.body().contains("\"startedAt\":"+exact));
                sameEvidence(source,Json.read(exported.body(),Transcript.class));
                for (String invalid:List.of(text.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"),
                        text.replace("\"schemaVersion\":2","\"schemaVersion\":2.0000000000000000001"),
                        text.replace("\"startedAt\":"+exact,"\"startedAt\":"+exact+".1"))) {
                    var refused=post(http,base+"/api/debates/import",invalid,false); assertEquals(400,refused.statusCode());
                    assertEquals(1,app.runs().size()); assertEquals(List.of(id),store.ids()); assertArrayEquals(original,Files.readAllBytes(saved));
                }
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
        try (var app=new DebateApplication(root,store,model -> { fail("Restart cannot infer"); return null; })) {
            assertEquals(1,app.runs().size()); sameEvidence(source,app.runs().get(0));
        }
    }
    @Test void largeUnicodeExportRoundTripsThroughHttpCommandsMenuAndRestart() throws Exception {
        TestFixtures.copyResources(root); var store=new RunStore(root.resolve("runs")); var source=largeEvidence(); var ids=new ArrayList<String>();
        try (var app=new DebateApplication(root,store,model -> { fail("Transfer must never construct providers"); return null; })) {
            var original=app.importTranscript(source); var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient();
                var exported=http.send(HttpRequest.newBuilder(URI.create(base+"/api/debates/"+original.id()+"/transcript?download=1")).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertEquals(200,exported.statusCode()); String body=exported.body(); assertTrue(body.getBytes(StandardCharsets.UTF_8).length>2*1024*1024);
                for (boolean chunked:List.of(false,true)) {
                    var imported=post(http,base+"/api/debates/import",body,chunked); assertEquals(201,imported.statusCode(),imported.body());
                    String id=(String)((Map<?,?>)Json.parse(imported.body())).get("id"); ids.add(id); sameEvidence(original.transcript(),app.find(id).transcript());
                }
                Path exportedFile=root.resolve("large Māori evidence.json"); var output=new ByteArrayOutputStream(); var stream=new PrintStream(output,true,StandardCharsets.UTF_8);
                var cli=new Main(base,new Scanner(""),stream); cli.command(new String[]{"transcript",original.id(),exportedFile.toString()}); assertEquals(body,Files.readString(exportedFile));
                cli.command(new String[]{"import",exportedFile.toString()}); String id=(String)((Map<?,?>)Json.parse(output.toString(StandardCharsets.UTF_8))).get("id"); ids.add(id); sameEvidence(original.transcript(),app.find(id).transcript());
                output.reset(); new Main(base,new Scanner("8\n"+exportedFile+"\n0\n"),stream).menu();
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("Request body is too large")); assertEquals(5,app.runs().size());
                app.runs().stream().map(Transcript::runId).filter(value -> !value.equals(original.id()) && !ids.contains(value)).forEach(ids::add);
                for (String importedId:ids) sameEvidence(original.transcript(),app.find(importedId).transcript());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
        try (var app=new DebateApplication(root,store,model -> { fail("Restart cannot resume inference"); return null; })) {
            for (String id:ids) sameEvidence(source,app.find(id).transcript());
        }
    }
    @Test void largeMalformedOrPrivateTranscriptsFailWithoutCreatingRuns() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Invalid imports cannot use providers"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient(); String valid=Json.write(largeEvidence());
                for (String invalid:List.of(valid.substring(0,valid.length()-1)+",\"privateAssignments\":\"PRIVATE_IMPORT_SENTINEL\"}",valid+" trailing")) {
                    var response=post(http,base+"/api/debates/import",invalid,true); assertEquals(400,response.statusCode()); assertFalse(response.body().contains("SENTINEL"));
                }
                assertTrue(app.runs().isEmpty()); assertTrue(new RunStore(root.resolve("runs")).ids().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void excessiveDeclarationsFailBeforeBodyReadAndOrdinaryRequestsKeepTheirBound() throws Exception {
        TestFixtures.copyResources(root);
        try (var app=new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("Rejected body cannot generate"); return null; })) {
            var server=WebServer.start(0,app);
            try {
                int port=server.getAddress().getPort(); String base="http://localhost:"+port;
                for (var endpoint:Map.of("/api/debates/import",64*1024*1024+1,"/api/settings/oversized",2*1024*1024+1).entrySet()) {
                    try (var socket=new Socket("localhost",port)) {
                        socket.setSoTimeout(3000); var out=socket.getOutputStream();
                        out.write(("POST "+endpoint.getKey()+" HTTP/1.1\r\nHost: localhost:"+port+"\r\nContent-Type: application/json\r\nContent-Length: "+endpoint.getValue()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                        String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                        assertNotNull(status); assertTrue(status.contains("400"),status);
                    }
                }
                var chunked=post(HttpClient.newHttpClient(),base+"/api/settings/oversized"," ".repeat(2*1024*1024+1),true);
                assertEquals(400,chunked.statusCode()); assertTrue(chunked.body().contains("Request body is too large"));
                assertTrue(app.runs().isEmpty()); assertTrue(app.settings().names().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
