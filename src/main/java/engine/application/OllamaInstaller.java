package engine.application;

import engine.config.OllamaConfig;
import engine.utils.*;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit portable installation; no existing binary is executed to test its integrity. */
final class OllamaInstaller {
    private final Path runtime;
    private final OllamaPackage artifact;
    private final HttpClient downloads=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    OllamaInstaller(Path root,OllamaPackage artifact) { this.runtime=root.toAbsolutePath().normalize().resolve(".runtime"); this.artifact=artifact; }
    Path directory() { return runtime.resolve("ollama-"+OllamaPackage.VERSION+"-"+artifact.platform()); }
    Path executable() { return directory().resolve(artifact.executable()); }
    Map<String,Object> metadata() { return artifact.metadata(); }
    boolean installed() {
        try {
            return Files.isRegularFile(executable(),LinkOption.NOFOLLOW_LINKS) && Files.readString(directory().resolve("parliament-install.sha256"))
                    .equals(artifact.sha256()+"\n"+sha256(executable())+"\n");
        } catch (IOException e) { return false; }
    }
    synchronized void install(OllamaConfig config,JobService.Context job) throws Exception {
        if (installed()) { job.update("Verified portable runtime already installed",metadata()); return; }
        Files.createDirectories(runtime);
        if (!runtime.toRealPath().startsWith(runtime.getParent().toRealPath())) throw new IOException("Runtime directory escapes project");
        Path cache=runtime.resolve("ollama-downloads/"+OllamaPackage.VERSION); Files.createDirectories(cache);
        Path archive=cache.resolve(artifact.name());
        if (Files.exists(archive)) {
            job.update("Verifying cached runtime archive",metadata());
            if (!verified(archive)) throw new IOException("Cached runtime archive failed checksum; preserve and remove it before retrying");
        } else download(config,archive,job);
        job.checkCancelled(); job.update("Extracting verified portable runtime",metadata());
        Path stage=runtime.resolve("ollama-extract-"+UUID.randomUUID());
        RuntimeArchive.extract(archive,artifact.format(),stage,job::checkCancelled);
        Path binary=stage.resolve(artifact.executable());
        if (!Files.isRegularFile(binary,LinkOption.NOFOLLOW_LINKS) || Files.size(binary)==0) throw new IOException("Runtime package has no executable");
        if (artifact.format()!=OllamaPackage.Format.ZIP && !binary.toFile().setExecutable(true,false)) throw new IOException("Cannot set executable permissions");
        AtomicFiles.write(stage.resolve("parliament-install.sha256"),artifact.sha256()+"\n"+sha256(binary)+"\n");
        job.checkCancelled();
        // Both absolute targets are inside the verified project runtime before moving a directory.
        Path destination=directory().toAbsolutePath().normalize();
        if (!destination.getParent().equals(runtime) || !stage.getParent().equals(runtime)) throw new IOException("Invalid runtime destination");
        if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
            Path preserved=runtime.resolve("ollama-incomplete-"+UUID.randomUUID());
            Files.move(destination,preserved);
        }
        Files.move(stage,destination); job.update("Portable runtime installed; no model weights downloaded",metadata());
    }
    private boolean verified(Path archive) throws IOException { return Files.size(archive)==artifact.bytes() && sha256(archive).equals(artifact.sha256()); }
    private void download(OllamaConfig config,Path archive,JobService.Context job) throws Exception {
        Path partial=archive.resolveSibling(archive.getFileName()+".part");
        long offset=Files.exists(partial) ? Files.size(partial) : 0;
        if (offset>artifact.bytes()) throw new IOException("Invalid partial runtime download; preserve and remove it before retrying");
        if (offset==artifact.bytes()) {
            if (!verified(partial)) throw new IOException("Partial runtime download failed checksum");
            Files.move(partial,archive); return;
        }
        job.update("Downloading pinned portable runtime ("+artifact.bytes()+" bytes)",metadata());
        var request=HttpRequest.newBuilder(artifact.url()).timeout(Duration.ofSeconds(config.downloadTimeoutSeconds())).GET();
        if (offset>0) request.header("Range","bytes="+offset+"-");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(config.downloadTimeoutSeconds());
        var response=downloads.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        try (var input=response.body()) {
            boolean resume=response.statusCode()==206;
            if (resume && (offset==0 || !response.headers().firstValue("Content-Range").orElse("")
                    .equals("bytes "+offset+"-"+(artifact.bytes()-1)+"/"+artifact.bytes()))) throw new IOException("Invalid resumed runtime response");
            if (!resume && response.statusCode()!=200) throw new IOException("Runtime download unsuccessful");
            long initial=resume ? offset : 0;
            RuntimeDownload.consume(input,deadline,job::checkCancelled,stream -> {
                try (var output=Files.newOutputStream(partial,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
                        resume ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    long total=initial, last=System.nanoTime(); byte[] buffer=new byte[65536]; int count;
                    while ((count=stream.read(buffer))!=-1) {
                        job.checkCancelled(); total+=count;
                        if (total>artifact.bytes()) throw new IOException("Runtime archive exceeds pinned size");
                        output.write(buffer,0,count);
                        if (System.nanoTime()-last>TimeUnit.SECONDS.toNanos(1)) {
                            job.update("Downloading runtime: "+total+" / "+artifact.bytes()+" bytes",Map.of("completedBytes",total,"totalBytes",artifact.bytes())); last=System.nanoTime();
                        }
                    }
                }
                return true;
            });
        }
        job.checkCancelled(); job.update("Verifying runtime archive checksum",metadata());
        if (!verified(partial)) throw new IOException("Runtime archive failed pinned size or checksum");
        Files.move(partial,archive);
    }
    static String sha256(Path file) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try (var input=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int count; while ((count=input.read(buffer))!=-1) digest.update(buffer,0,count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
