package engine.application;

import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** Run with only target/test-classes and the shaded application JAR to verify bundled readers/JNI. */
public final class OllamaPackageSmoke {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(root);
        byte[] payload="Tiny archive-reader smoke; no real runtime is executed.".getBytes(StandardCharsets.UTF_8);
        for (var format:OllamaPackage.Format.values()) {
            Path archive=root.resolve(format.name()+".archive"),stage=root.resolve(format.name()+"-extracted");
            try (var file=Files.newOutputStream(archive)) {
                if (format==OllamaPackage.Format.ZIP) {
                    try (var zip=new ZipOutputStream(file)) { zip.putNextEntry(new ZipEntry("bin/ollama")); zip.write(payload); zip.closeEntry(); }
                } else {
                    try (OutputStream compressor=format==OllamaPackage.Format.GZIP_TAR ? new GZIPOutputStream(file) : new ZstdOutputStream(file);
                         var tar=new TarArchiveOutputStream(compressor)) {
                        var entry=new TarArchiveEntry("bin/ollama"); entry.setSize(payload.length); tar.putArchiveEntry(entry); tar.write(payload); tar.closeArchiveEntry();
                    }
                }
            }
            RuntimeArchive.extract(archive,format,stage,() -> {});
            if (!java.util.Arrays.equals(payload,Files.readAllBytes(stage.resolve("bin/ollama")))) throw new IOException("Bundled archive reader mismatch");
            System.out.println(format+" bundled reader PASS");
        }
    }
    private OllamaPackageSmoke() { }
}
