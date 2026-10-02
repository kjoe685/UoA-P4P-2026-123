package engine.application;

import com.sun.net.httpserver.*;
import engine.config.OllamaConfig;
import engine.utils.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Tiny HTTP/package/process stand-ins: never install or run an actual model. */
public final class OllamaFixture implements AutoCloseable {
    final HttpServer packages;
    HttpServer service;
    final int port;
    final byte[] archive;
    final OllamaPackage artifact;
    final AtomicInteger downloads=new AtomicInteger(),pulls=new AtomicInteger(),chats=new AtomicInteger(),starts=new AtomicInteger();
    final List<Map<?,?>> requests=new CopyOnWriteArrayList<>();
    final Map<String,Map<String,Object>> models=new ConcurrentHashMap<>();
    volatile String range;
    volatile ProcessBuilder builder;
    volatile CountDownLatch stall;
    volatile CountDownLatch stallArchive;
    volatile boolean incompatible,badPull,incompletePull,badArchive,wrongRange,ignoreRange,aliasRace;
    volatile String version="0.35.0";
    final AtomicInteger remoteForwards=new AtomicInteger();
    volatile String additionalProgress;
    public OllamaFixture() throws Exception {
        try (var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        var bytes=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("ollama.exe")); zip.write("TINY_FAKE_EXECUTABLE_NEVER_RUN".getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        archive=bytes.toByteArray(); packages=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        packages.setExecutor(Executors.newCachedThreadPool()); packages.createContext("/runtime.zip",exchange -> {
            try {
                downloads.incrementAndGet(); range=exchange.getRequestHeaders().getFirst("Range");
                int offset=range==null || ignoreRange ? 0 : Integer.parseInt(range.substring(6,range.length()-1));
                if (offset>0) exchange.getResponseHeaders().set("Content-Range","bytes "+(wrongRange ? offset+1 : offset)+"-"+(archive.length-1)+"/"+archive.length);
                byte[] payload=archive.clone(); if (badArchive) payload[10]^=1;
                exchange.sendResponseHeaders(offset==0 ? 200 : 206,payload.length-offset);
                if (stallArchive!=null) {
                    exchange.getResponseBody().write(payload,offset,8); exchange.getResponseBody().flush();
                    try { stallArchive.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    offset+=8;
                }
                exchange.getResponseBody().write(payload,offset,payload.length-offset);
            } finally { exchange.close(); }
        }); packages.start();
        artifact=new OllamaPackage("fixture","runtime.zip",archive.length,Hashes.sha256(archive),OllamaPackage.Format.ZIP,"ollama.exe",
                URI.create("http://localhost:"+packages.getAddress().getPort()+"/runtime.zip"));
    }
    public OllamaConfig config() { return new OllamaConfig(1,URI.create("http://localhost:"+port),16384,3,3); }
    void cache(String name,boolean local) {
        Map<String,Object> model=new HashMap<>(Map.of("name",name,"model",name,"size",1234,"digest","a".repeat(64)));
        if (!local) model.put("remote_model","REMOTE_SECRET_SENTINEL"); models.put(name,model);
    }
    void startService() throws IOException {
        service=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),0);
        service.setExecutor(Executors.newCachedThreadPool());
        service.createContext("/",exchange -> {
            try {
                String path=exchange.getRequestURI().getPath();
                if (incompatible) { send(exchange,"{\"unexpected\":\"BODY_SECRET_SENTINEL\"}"); return; }
                if (path.equals("/api/version")) send(exchange,Json.write(Map.of("version",version)));
                else if (path.equals("/api/tags")) send(exchange,Json.write(Map.of("models",models.values())));
                else if (path.equals("/api/pull")) {
                    pulls.incrementAndGet(); var body=(Map<?,?>)Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); requests.add(body);
                    exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write("{\"status\":\"RAW_REMOTE_SECRET_SENTINEL\",\"total\":100}\n".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                    if (additionalProgress!=null) exchange.getResponseBody().write(additionalProgress.getBytes(StandardCharsets.UTF_8));
                    var block=stall; if (block!=null) try { block.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (badPull) exchange.getResponseBody().write("{\"error\":\"RAW_ERROR_SECRET_SENTINEL\"}\n".getBytes(StandardCharsets.UTF_8));
                    else if (!incompletePull) {
                        String name=body.get("model").toString();
                        if (name.endsWith(":local")) name=name.substring(0,name.length()-6);
                        cache(name.contains(":") ? name : name+":latest",true);
                        exchange.getResponseBody().write("{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8));
                    }
                } else if (path.equals("/api/chat")) {
                    chats.incrementAndGet(); var body=(Map<?,?>)Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); requests.add(body);
                    if (aliasRace) {
                        // The prior inventory was local; its alias changes before the server processes chat.
                        if (body.get("model").toString().endsWith(":local")) {
                            exchange.sendResponseHeaders(404,-1); return;
                        }
                        remoteForwards.incrementAndGet();
                    }
                    send(exchange,"{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"content\":\"Synthetic local speech; software QA only.\"}}");
                } else { exchange.sendResponseHeaders(404,-1); }
            } finally { exchange.close(); }
        }); service.start();
    }
    public ManagedOllama manager(Path root) {
        return new ManagedOllama(root,artifact,command -> { starts.incrementAndGet(); builder=command; startService(); return new FakeProcess(this); });
    }
    static void send(HttpExchange exchange,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
    }
    void stopService() { if (service!=null) { service.stop(0); ((ExecutorService)service.getExecutor()).shutdownNow(); service=null; } }
    @Override public void close() { if (stall!=null) stall.countDown(); if (stallArchive!=null) stallArchive.countDown(); stopService(); packages.stop(0); ((ExecutorService)packages.getExecutor()).shutdownNow(); }
    private static final class FakeProcess extends Process {
        private final OllamaFixture fixture;
        private boolean alive=true;
        FakeProcess(OllamaFixture fixture) { this.fixture=fixture; }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { alive=false; fixture.stopService(); }
        public Process destroyForcibly() { destroy(); return this; }
        public boolean isAlive() { return alive; }
        public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
