package engine.application;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import engine.utils.Hashes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaInstallerTest {
    @TempDir Path root;
    @Test void installReusesVerifiedDownloadPreservesIncompleteDirectoryAndRepairsChangedExecutable() throws Exception {
        try (var fixture=new OllamaFixture(); var jobs=new JobService(root.resolve("jobs"))) {
            var installer=new OllamaInstaller(root,fixture.artifact); Path cache=cache(installer,fixture);
            Files.write(cache,fixture.archive); Files.createDirectories(installer.directory()); Files.writeString(installer.directory().resolve("owner-marker"),"PRESERVE");
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state()); assertTrue(installer.installed()); assertEquals(0,fixture.downloads.get());
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state());
            Files.writeString(installer.executable(),"TRUNCATED"); assertFalse(installer.installed());
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state()); assertTrue(installer.installed());
            try (var files=Files.walk(root.resolve(".runtime"))) { assertTrue(files.filter(Files::isRegularFile).anyMatch(path -> path.getFileName().toString().equals("owner-marker"))); }
            assertEquals(0,fixture.downloads.get());
        }
    }
    @Test void partialDownloadResumesAtExactOffsetAndFinalBytesMatchPinnedIdentity() throws Exception {
        try (var fixture=new OllamaFixture(); var jobs=new JobService(root.resolve("jobs"))) {
            var installer=new OllamaInstaller(root,fixture.artifact); Path cache=cache(installer,fixture);
            Files.write(cache.resolveSibling(cache.getFileName()+".part"),Arrays.copyOf(fixture.archive,31));
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state());
            assertEquals("bytes=31-",fixture.range); assertEquals(1,fixture.downloads.get()); assertArrayEquals(fixture.archive,Files.readAllBytes(cache)); assertTrue(installer.installed());
        }
    }
    @Test void corruptCacheAndCorruptCompletedPartialNeverExtractOrExecute() throws Exception {
        try (var fixture=new OllamaFixture(); var jobs=new JobService(root.resolve("jobs"))) {
            var installer=new OllamaInstaller(root,fixture.artifact); Path cache=cache(installer,fixture);
            byte[] corrupt=fixture.archive.clone(); corrupt[10]^=1; Files.write(cache,corrupt);
            assertEquals(BackgroundJob.State.FAILED,install(jobs,installer,fixture).state()); assertFalse(installer.installed()); assertEquals(0,fixture.downloads.get());
            Files.move(cache,cache.resolveSibling(cache.getFileName()+".part"));
            assertEquals(BackgroundJob.State.FAILED,install(jobs,installer,fixture).state()); assertFalse(Files.exists(installer.directory())); assertEquals(0,fixture.downloads.get());
        }
    }
    @Test void checksumFailureAndWrongRangeResponseNeverPublishABinary() throws Exception {
        try (var fixture=new OllamaFixture(); var jobs=new JobService(root.resolve("jobs"))) {
            var installer=new OllamaInstaller(root,fixture.artifact); Path cache=cache(installer,fixture); fixture.badArchive=true;
            assertEquals(BackgroundJob.State.FAILED,install(jobs,installer,fixture).state()); assertFalse(installer.installed()); assertFalse(Files.exists(cache));
            fixture.badArchive=false; fixture.wrongRange=true; Files.write(cache.resolveSibling(cache.getFileName()+".part"),Arrays.copyOf(fixture.archive,31));
            assertEquals(BackgroundJob.State.FAILED,install(jobs,installer,fixture).state()); assertFalse(Files.exists(installer.directory()));
            fixture.wrongRange=false; fixture.ignoreRange=true;
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state()); assertArrayEquals(fixture.archive,Files.readAllBytes(cache));
        }
    }
    @Test void cancelledRuntimeDownloadRetainsPartialAndReleasesWorkerForRetry() throws Exception {
        try (var fixture=new OllamaFixture(); var jobs=new JobService(root.resolve("jobs"))) {
            fixture.stallArchive=new java.util.concurrent.CountDownLatch(1); var installer=new OllamaInstaller(root,fixture.artifact); Path cache=cache(installer,fixture);
            var job=jobs.submit("install",null,Map.of(),context -> { installer.install(fixture.config(),context); return true; });
            Path partial=cache.resolveSibling(cache.getFileName()+".part"); long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(partial) || Files.size(partial)==0) && System.nanoTime()<deadline) Thread.sleep(10);
            assertTrue(Files.size(partial)>0); jobs.cancel(job.id());
            var next=jobs.submit("after-cancel",null,Map.of(),context -> true); assertEquals(BackgroundJob.State.COMPLETE,ManagedOllamaTest.finish(jobs,next.id()).state()); assertFalse(installer.installed());
            fixture.stallArchive.countDown(); fixture.stallArchive=null;
            assertEquals(BackgroundJob.State.COMPLETE,install(jobs,installer,fixture).state()); assertNotNull(fixture.range); assertTrue(installer.installed());
        }
    }
    @Test void missingOrEmptyExecutableCannotPublishAnInstallMarker() throws Exception {
        for (String file:List.of("not-ollama.txt","ollama.exe")) {
            Path project=root.resolve(file); byte[] archive=zip(Map.of(file,new byte[0]));
            var artifact=new OllamaPackage("fixture","missing.zip",archive.length,Hashes.sha256(archive),OllamaPackage.Format.ZIP,"ollama.exe",java.net.URI.create("http://localhost/missing.zip"));
            var installer=new OllamaInstaller(project,artifact); Path cache=project.resolve(".runtime/ollama-downloads/"+OllamaPackage.VERSION+"/missing.zip"); Files.createDirectories(cache.getParent()); Files.write(cache,archive);
            try (var jobs=new JobService(project.resolve("jobs"))) {
                var job=jobs.submit("install",null,Map.of(),context -> { installer.install(engine.config.OllamaConfig.defaults(),context); return true; });
                assertEquals(BackgroundJob.State.FAILED,ManagedOllamaTest.finish(jobs,job.id()).state()); assertFalse(installer.installed()); assertFalse(Files.exists(installer.directory()));
            }
        }
    }
    @Test void zipGzipAndZstdPackagesExtractNestedFilesWithoutAnExternalUtility() throws Exception {
        for (var format:OllamaPackage.Format.values()) {
            Path archive=root.resolve(format.name()),output=root.resolve(format.name()+"-out");
            byte[] payload="SMALL_OFFLINE_RUNTIME_FIXTURE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(archive,format==OllamaPackage.Format.ZIP ? zip(Map.of("bin/ollama",payload)) : tar(format,"bin/ollama",payload,null,false));
            RuntimeArchive.extract(archive,format,output,() -> {}); assertArrayEquals(payload,Files.readAllBytes(output.resolve("bin/ollama")));
        }
    }
    @Test void archiveTraversalAbsoluteWindowsPathsAndLinksAreRejectedBeforePublishing() throws Exception {
        int index=0;
        for (String name:List.of("../outside","/outside","C:/outside","dir\\outside","bin/file:stream")) {
            Path archive=root.resolve("unsafe-"+index),output=root.resolve("unsafe-out-"+index++); Files.write(archive,zip(Map.of(name,new byte[]{1})));
            assertThrows(IOException.class,() -> RuntimeArchive.extract(archive,OllamaPackage.Format.ZIP,output,() -> {}));
        }
        for (boolean symbolic:List.of(true,false)) {
            Path archive=root.resolve("link-"+symbolic),output=root.resolve("link-out-"+symbolic);
            Files.write(archive,tar(OllamaPackage.Format.GZIP_TAR,"bad",new byte[0],"../outside",symbolic));
            assertThrows(IOException.class,() -> RuntimeArchive.extract(archive,OllamaPackage.Format.GZIP_TAR,output,() -> {}));
        }
        assertFalse(Files.exists(root.getParent().resolve("outside")));
    }
    @Test void unresolvedHardLinksAndDuplicateFilesCannotCreateAValidStagingDirectory() throws Exception {
        Path archive=root.resolve("bad-link.tar.gz"); Files.write(archive,tar(OllamaPackage.Format.GZIP_TAR,"bad",new byte[0],"missing",false));
        assertThrows(IOException.class,() -> RuntimeArchive.extract(archive,OllamaPackage.Format.GZIP_TAR,root.resolve("bad-link"),() -> {}));
        var bytes=new ByteArrayOutputStream();
        try (var gzip=new GZIPOutputStream(bytes); var tar=new TarArchiveOutputStream(gzip)) {
            for (int i=0;i<2;i++) { var entry=new TarArchiveEntry("duplicate"); entry.setSize(1); tar.putArchiveEntry(entry); tar.write(1); tar.closeArchiveEntry(); }
        }
        Files.write(archive,bytes.toByteArray()); assertThrows(IOException.class,() -> RuntimeArchive.extract(archive,OllamaPackage.Format.GZIP_TAR,root.resolve("duplicate"),() -> {}));
    }
    @Test void archiveExtractionChecksCancellationBeforePublishing() throws Exception {
        Path archive=root.resolve("cancel.zip"); Files.write(archive,zip(Map.of("ollama.exe",new byte[100])));
        assertThrows(java.util.concurrent.CancellationException.class,() -> RuntimeArchive.extract(archive,OllamaPackage.Format.ZIP,root.resolve("cancel"),() -> { throw new java.util.concurrent.CancellationException(); }));
        assertFalse(Files.exists(root.resolve("cancel/ollama.exe")));
    }
    private Path cache(OllamaInstaller installer,OllamaFixture fixture) throws IOException {
        Path path=root.resolve(".runtime/ollama-downloads/"+OllamaPackage.VERSION+"/"+fixture.artifact.name()); Files.createDirectories(path.getParent()); return path;
    }
    private BackgroundJob install(JobService jobs,OllamaInstaller installer,OllamaFixture fixture) throws Exception {
        var job=jobs.submit("install",null,Map.of(),context -> { installer.install(fixture.config(),context); return true; }); return ManagedOllamaTest.finish(jobs,job.id());
    }
    static byte[] zip(Map<String,byte[]> files) throws IOException {
        var bytes=new ByteArrayOutputStream(); try (var zip=new ZipOutputStream(bytes)) {
            for (var file:files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry(); }
        } return bytes.toByteArray();
    }
    static byte[] tar(OllamaPackage.Format format,String path,byte[] data,String target,boolean symbolic) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try (OutputStream compressor=format==OllamaPackage.Format.GZIP_TAR ? new GZIPOutputStream(bytes) : new ZstdOutputStream(bytes); var tar=new TarArchiveOutputStream(compressor)) {
            var entry=target==null ? new TarArchiveEntry(path) : new TarArchiveEntry(path,symbolic ? TarConstants.LF_SYMLINK : TarConstants.LF_LINK);
            if (target!=null) entry.setLinkName(target); else { entry.setSize(data.length); entry.setMode(0755); }
            tar.putArchiveEntry(entry); if (target==null) tar.write(data); tar.closeArchiveEntry();
        } return bytes.toByteArray();
    }
}
