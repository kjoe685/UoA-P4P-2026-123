package engine.application;

import engine.TestFixtures;
import engine.config.ConfigurationSnapshot;
import engine.prompt.TemplateName;
import engine.utils.*;
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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceEncodingTest {
    @TempDir Path root;
    static byte[] malformed(String text) throws IOException {
        int at=text.indexOf("<BAD_UTF8>"); assertTrue(at>=0); var bytes=new ByteArrayOutputStream();
        bytes.write(text.substring(0,at).getBytes(StandardCharsets.UTF_8)); bytes.write(new byte[]{(byte)0xc3,0x28});
        bytes.write(text.substring(at+10).getBytes(StandardCharsets.UTF_8)); return bytes.toByteArray();
    }
    @Test void malformedSourceFilesFailBeforeProvidersOrStorageThroughApplicationAndApi() throws Exception {
        TestFixtures.copyResources(root); var calls=new AtomicInteger(); var store=new RunStore(root.resolve("runs"));
        try (var app=new DebateApplication(root,store,model -> { calls.incrementAndGet(); return new engine.demo.DemoChatManager(); })) {
            var server=WebServer.start(0,app);
            try {
                String base="http://localhost:"+server.getAddress().getPort(); var http=HttpClient.newHttpClient();
                for (String path:List.of("prompts/"+TemplateName.PERSONA.fileName(),"config/engine.json")) {
                    Path file=root.resolve(path); byte[] original=Files.readAllBytes(file); String text=new String(original,StandardCharsets.UTF_8);
                    text=path.startsWith("prompts") ? text+"\nPRIVATE_ENCODING_SENTINEL <BAD_UTF8>" : text.replace("Whether", "PRIVATE_ENCODING_SENTINEL <BAD_UTF8> Whether");
                    Files.write(file,malformed(text));
                    try {
                        var error=assertThrows(IllegalArgumentException.class,() -> app.start(TestFixtures.settings()));
                        assertTrue(error.getMessage().contains("UTF-8")); assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL"));
                        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/debates")).header("Content-Type","application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(Json.write(TestFixtures.settings()))).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(400,response.statusCode()); assertTrue(response.body().contains("UTF-8")); assertFalse(response.body().contains("SENTINEL"));
                        assertEquals(0,calls.get()); assertTrue(app.runs().isEmpty()); assertTrue(store.ids().isEmpty());
                    } finally { Files.write(file,original); }
                }
            } finally { server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow(); }
        }
    }
    @Test void surrogateAssetCandidatesCannotBeRewrittenOrSaved() throws Exception {
        TestFixtures.copyResources(root); String path="prompts/"+TemplateName.PERSONA.fileName(); var assets=new AssetService(root); String original=assets.read(path);
        for (String invalid:List.of(original+"\uD800PRIVATE_ENCODING_SENTINEL",original+"\uDC00PRIVATE_ENCODING_SENTINEL")) {
            for (boolean save:List.of(false,true)) {
                var error=assertThrows(IllegalArgumentException.class,() -> assets.update(path,invalid,save));
                assertNull(error.getCause()); assertFalse(error.getMessage().contains("SENTINEL")); assertEquals(original,assets.read(path));
            }
        }
    }
    @Test void validUnicodeAndLiteralReplacementCharacterRetainExactFrozenContentsAndHashes() throws Exception {
        TestFixtures.copyResources(root); String path="prompts/"+TemplateName.PERSONA.fileName();
        String text=Files.readString(root.resolve(path))+"\r\nMāori 😀 e\u0301 \uFFFD valid text.\r\n"; byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(path),bytes); var snapshot=ConfigurationSnapshot.load(root);
        assertEquals(text,snapshot.sourceContents().get(path)); assertEquals(Hashes.sha256(bytes),snapshot.sourceHashes().get(path));
        var replacement=ConfigurationSnapshot.load(root,Map.of(path,text)); assertEquals(snapshot.sourceHashes(),replacement.sourceHashes());
        Files.writeString(root.resolve(path),text+"Later edit");
        assertEquals(text,snapshot.sourceContents().get(path)); assertEquals(Hashes.sha256(text),snapshot.sourceHashes().get(path));
        assertNotEquals(snapshot.sourceHashes().get(path),ConfigurationSnapshot.load(root).sourceHashes().get(path));
        var values=new HashMap<String,String>(); TemplateName.PERSONA.placeholders().forEach(name -> values.put(name,"Fixture"));
        assertTrue(snapshot.template(TemplateName.PERSONA).render(values).contains("Māori 😀 e\u0301 \uFFFD valid text."));
    }
}
