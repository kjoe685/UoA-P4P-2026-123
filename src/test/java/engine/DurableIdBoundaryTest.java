package engine;

import engine.application.*;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class DurableIdBoundaryTest {
    @TempDir Path root;
    private DebateApplication app() throws IOException {
        TestFixtures.copyResources(root);
        return new DebateApplication(root,new RunStore(root.resolve("runs")),model -> { fail("No inference"); return null; });
    }
    @Test void malformedStorageIdsHaveStaticErrorsBeforeAccessingFiles() throws Exception {
        try (var app=app()) {
            var store=new RunStore(root.resolve("runs"));
            for (String id:Arrays.asList("INPUT_SECRET_SENTINEL",null,"1-1-1-1-1","AAAAAAAA-0000-0000-0000-000000000000")) {
                assertAll(
                        () -> {
                            var error=assertThrows(IllegalArgumentException.class,() -> app.pilots().read(id));
                            assertEquals("Invalid pilot id",error.getMessage()); assertNull(error.getCause());
                        },
                        () -> {
                            var error=assertThrows(IllegalArgumentException.class,() -> store.transcript(id));
                            assertEquals("Invalid run id",error.getMessage()); assertNull(error.getCause());
                        });
            }
            assertFalse(Files.exists(root.resolve("runs/pilots"))); assertTrue(app.runs().isEmpty());
        }
    }
    @Test void malformedPilotDownloadCannotEchoInputOrInstallAttachmentHeaders() throws Exception {
        try (var app=app()) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/pilots/INPUT_SECRET_SENTINEL?download=1")).build(),HttpResponse.BodyHandlers.ofString());
                assertAll(
                        () -> assertEquals(400,response.statusCode()),
                        () -> assertFalse(response.body().contains("SENTINEL")),
                        () -> assertTrue(response.headers().firstValue("Content-Disposition").isEmpty()));
                assertTrue(app.pilots().list().isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void missingCanonicalPilotDownloadIsAPlainError() throws Exception {
        try (var app=app()) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/pilots/"+UUID.randomUUID()+"?download=1")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(404,response.statusCode()); assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void validSyntheticPilotAttachmentAndTerminalRefusalsPreserveTheirContracts() throws Exception {
        try (var app=app()) {
            var dataset=PilotTest.fixture("synthetic_fixture",200);
            String id=(String)app.pilots().importDataset(PilotTest.body(dataset)).get("id");
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort();
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/pilots/"+id+"?download=1")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode()); assertEquals(dataset,Json.read(response.body(),engine.evaluation.local.PilotDataset.class));
                assertEquals("attachment; filename=\"parliament-"+id+"-pilot.json\"",response.headers().firstValue("Content-Disposition").orElseThrow());
                var output=new ByteArrayOutputStream(); var print=new PrintStream(output,true,StandardCharsets.UTF_8);
                Path export=root.resolve("existing.json"); Files.writeString(export,"OWNER_SENTINEL");
                var error=assertThrows(IllegalStateException.class,() -> new Main(base,new Scanner(""),print).command(new String[]{"pilot","show","INPUT_SECRET_SENTINEL",export.toString()}));
                assertFalse(error.getMessage().contains("SENTINEL")); assertEquals("OWNER_SENTINEL",Files.readString(export));
                new Main(base,new Scanner("17\nshow\nINPUT_SECRET_SENTINEL\n\n0\n"),print).menu();
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("INPUT_SECRET_SENTINEL"));
                assertTrue(output.toString().contains("0 Exit"));
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
}
