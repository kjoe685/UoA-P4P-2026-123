package engine.application;

import engine.TestFixtures;
import engine.provider.ProviderFactory;
import engine.utils.Json;
import engine.web.WebServer;
import java.nio.file.*;
import java.util.concurrent.*;

/** Developer-only isolated browser QA backend using tiny packages and synthetic HTTP inference. */
public final class OllamaBrowserFixture {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("config"))) TestFixtures.copyResources(root);
        var fixture=new OllamaFixture(); var manager=fixture.manager(root);
        Files.writeString(root.resolve("config/ollama.json"),Json.write(fixture.config()));
        var factory=new ProviderFactory(root,manager);
        var application=new DebateApplication(root,new RunStore(root.resolve("runs")),factory::forModel,null,manager);
        var server=WebServer.start(Integer.parseInt(args[1]),application);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            application.close(); fixture.close(); server.stop(0); ((ExecutorService)server.getExecutor()).shutdownNow();
        }));
        System.out.println("FAKE OLLAMA QA ONLY: http://localhost:"+server.getAddress().getPort());
        System.out.println("Isolated evidence: "+root);
    }
    private OllamaBrowserFixture() { }
}
