package engine;

import com.sun.nio.file.ExtendedOpenOption;
import engine.application.*;
import engine.utils.Json;
import engine.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;

class JobRecoveryTest {
    @TempDir Path root;
    private record Saved(BackgroundJob job,Path file,Path input,byte[] jobBytes,byte[] inputBytes) { }

    private Saved saved(BackgroundJob.State state) throws Exception {
        TestFixtures.copyResources(root);
        String id=UUID.randomUUID().toString();
        var job=new BackgroundJob(id,"llm-evaluation",UUID.randomUUID().toString(),123,
                state==BackgroundJob.State.COMPLETE ? 456L : null,state,"Committed progress",
                Map.of("retained","Partial Māori evidence ā 🙂."));
        Path directory=root.resolve("runs/jobs").resolve(id); Files.createDirectories(directory);
        Path file=directory.resolve("job.json"),input=directory.resolve("input.json");
        Files.writeString(file,Json.write(job)); Files.writeString(input,"PRIVATE_INPUT_SENTINEL");
        return new Saved(job,file,input,Files.readAllBytes(file),Files.readAllBytes(input));
    }
    private DebateApplication reopen() {
        return new DebateApplication(root,new RunStore(root.resolve("runs")),model -> {
            throw new AssertionError("Job recovery must not construct providers or resume inference");
        });
    }
    private static void assertInterrupted(Saved saved,BackgroundJob recovered) {
        assertAll(
                () -> assertEquals(BackgroundJob.State.INTERRUPTED,recovered.state()),
                () -> assertEquals(saved.job().result(),recovered.result()),
                () -> assertEquals(saved.job().runId(),recovered.runId()),
                () -> assertEquals(saved.job().createdAt(),recovered.createdAt()),
                () -> assertNotNull(recovered.endedAt()));
    }
    @ParameterizedTest @EnumSource(value=BackgroundJob.State.class,names={"QUEUED","RUNNING"})
    void unfinishedJobRecoveryIsDurableAndRetainsPartialResultsWithoutInference(BackgroundJob.State state) throws Exception {
        var saved=saved(state); byte[] recoveredBytes;
        try (var app=reopen()) {
            var recovered=app.background().find(saved.job().id()); assertInterrupted(saved,recovered);
            assertEquals(recovered,Json.read(Files.readString(saved.file()),BackgroundJob.class));
            recoveredBytes=Files.readAllBytes(saved.file());
        }
        try (var app=reopen()) { assertInterrupted(saved,app.background().find(saved.job().id())); }
        assertArrayEquals(recoveredBytes,Files.readAllBytes(saved.file()));
        assertArrayEquals(saved.inputBytes(),Files.readAllBytes(saved.input()));
    }
    @Test void terminalJobRecoveryPreservesExactSavedBytes() throws Exception {
        var saved=saved(BackgroundJob.State.COMPLETE);
        try (var app=reopen()) { assertEquals(saved.job(),app.background().find(saved.job().id())); }
        assertArrayEquals(saved.jobBytes(),Files.readAllBytes(saved.file()));
        assertArrayEquals(saved.inputBytes(),Files.readAllBytes(saved.input()));
    }
    /** Windows sharing denies atomic replacement while leaving the committed file readable. */
    @EnabledOnOs(OS.WINDOWS)
    @ParameterizedTest @EnumSource(value=BackgroundJob.State.class,names={"QUEUED","RUNNING"})
    void refusedRestartSaveCannotAdvertiseOrExportUnrecoveredJobsAndRetryRetainsResults(BackgroundJob.State state) throws Exception {
        var saved=saved(state); String id=saved.job().id();
        var diagnostics=new ByteArrayOutputStream(); var previousError=System.err;
        try (var locked=FileChannel.open(saved.file(),StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)) {
            System.setErr(new PrintStream(diagnostics,true,StandardCharsets.UTF_8));
            try (var app=reopen()) {
                assertTrue(app.background().list().isEmpty(),"A refused restart save must not advertise a job with no worker");
                assertThrows(NoSuchElementException.class,() -> app.background().find(id));
                verifyInterfaces(app,saved,false);
            } finally { System.setErr(previousError); }
            assertArrayEquals(saved.jobBytes(),Files.readAllBytes(saved.file()));
        } finally { System.setErr(previousError); }
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("file replacement"));
        assertFalse(diagnostics.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
        assertFalse(diagnostics.toString(StandardCharsets.UTF_8).contains(saved.file().toString()));
        assertArrayEquals(saved.inputBytes(),Files.readAllBytes(saved.input()));
        try (var app=reopen()) {
            assertInterrupted(saved,app.background().find(id)); verifyInterfaces(app,saved,true);
        }
        assertInterrupted(saved,Json.read(Files.readString(saved.file()),BackgroundJob.class));
        assertArrayEquals(saved.inputBytes(),Files.readAllBytes(saved.input()));
    }
    private void verifyInterfaces(DebateApplication app,Saved saved,boolean recovered) throws Exception {
        String id=saved.job().id(); var server=WebServer.start(0,app);
        try {
            String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient();
            var list=http.send(HttpRequest.newBuilder(URI.create(base+"/api/jobs")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,list.statusCode()); assertEquals(recovered,list.body().contains(id));
            var detail=http.send(HttpRequest.newBuilder(URI.create(base+"/api/jobs/"+id)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(recovered ? 200 : 404,detail.statusCode());
            var report=http.send(HttpRequest.newBuilder(URI.create(base+"/api/jobs/"+id+"/report")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(recovered ? 200 : 404,report.statusCode());
            assertFalse(list.body().contains("SENTINEL")); assertFalse(detail.body().contains("SENTINEL")); assertFalse(report.body().contains("SENTINEL"));
            var bytes=new ByteArrayOutputStream(); var output=new PrintStream(bytes,true,StandardCharsets.UTF_8);
            var cli=new Main(base,new Scanner(""),output); cli.command(new String[]{"jobs"});
            assertEquals(recovered,bytes.toString(StandardCharsets.UTF_8).contains(id));
            Path export=root.resolve("existing-report.json"); Files.writeString(export,"OWNER_DESTINATION_SENTINEL");
            if (recovered) {
                assertInterrupted(saved,Json.read(detail.body(),BackgroundJob.class));
                assertEquals(saved.job().result(),Json.parse(report.body()));
                cli.command(new String[]{"report",id,export.toString()});
                assertEquals(saved.job().result(),Json.parse(Files.readString(export)));
            } else {
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"job",id}));
                assertThrows(IllegalStateException.class,() -> cli.command(new String[]{"report",id,export.toString()}));
                assertEquals("OWNER_DESTINATION_SENTINEL",Files.readString(export));
            }
            bytes.reset(); new Main(base,new Scanner("14\n\n0\n"),output).menu();
            assertEquals(recovered,bytes.toString(StandardCharsets.UTF_8).contains(id));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
            bytes.reset(); new Main(base,new Scanner("14\n"+id+"\nreport\n\n0\n"),output).menu();
            assertEquals(recovered,bytes.toString(StandardCharsets.UTF_8).contains("Partial Māori evidence ā 🙂."));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("SENTINEL"));
        } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
    }
}
