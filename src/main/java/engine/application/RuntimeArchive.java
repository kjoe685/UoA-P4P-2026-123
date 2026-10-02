package engine.application;

import com.github.luben.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Extract a verified package into a new staging directory, never through external links. */
final class RuntimeArchive {
    private record Link(Path path,Path target,boolean symbolic) { }
    private static final long MAX_UNPACKED=12_000_000_000L;
    private final Path root;
    private final Runnable check;
    private final List<Link> links=new ArrayList<>();
    private long unpacked;
    private int entries;
    private RuntimeArchive(Path root,Runnable check) { this.root=root.toAbsolutePath().normalize(); this.check=check; }
    static void extract(Path archive,OllamaPackage.Format format,Path destination,Runnable check) throws IOException {
        Files.createDirectory(destination);
        var reader=new RuntimeArchive(destination,check);
        try (InputStream input=Files.newInputStream(archive)) {
            if (format==OllamaPackage.Format.ZIP) {
                try (var zip=new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry=zip.getNextEntry())!=null) reader.entry(zip,entry.getName(),entry.isDirectory(),false,null,false,false);
                }
            } else {
                try (var tar=new TarArchiveInputStream(format==OllamaPackage.Format.GZIP_TAR ? new GZIPInputStream(input) : new ZstdInputStream(input))) {
                    TarArchiveEntry entry;
                    while ((entry=tar.getNextEntry())!=null) {
                        if (!tar.canReadEntryData(entry) || !(entry.isDirectory() || entry.isFile() || entry.isSymbolicLink() || entry.isLink()))
                            throw new IOException("Unsupported runtime archive entry");
                        reader.entry(tar,entry.getName(),entry.isDirectory(),entry.isSymbolicLink() || entry.isLink(),entry.getLinkName(),entry.isSymbolicLink(),(entry.getMode() & 0111)!=0);
                    }
                }
            }
        }
        reader.finishLinks(); check.run();
    }
    private Path path(String name) throws IOException {
        if (name==null || name.isBlank() || name.indexOf('\0')>=0 || name.contains("\\") || name.startsWith("/") || name.contains(":"))
            throw new IOException("Invalid runtime archive path");
        Path path=root.resolve(name).normalize();
        if (!path.startsWith(root)) throw new IOException("Runtime archive path escapes staging");
        return path;
    }
    private void entry(InputStream input,String name,boolean directory,boolean link,String linkName,boolean symbolic,boolean executable) throws IOException {
        check.run(); if (++entries>10000) throw new IOException("Runtime archive contains too many entries");
        Path target=path(name);
        if (directory) { Files.createDirectories(target); return; }
        if (target.equals(root) || Files.exists(target,LinkOption.NOFOLLOW_LINKS) || links.stream().anyMatch(item -> item.path.equals(target)))
            throw new IOException("Duplicate runtime archive file");
        Files.createDirectories(target.getParent());
        if (link) {
            if (linkName==null || linkName.isBlank() || linkName.contains("\\") || linkName.startsWith("/") || linkName.contains(":"))
                throw new IOException("Invalid runtime archive link");
            Path source=(symbolic ? target.getParent() : root).resolve(linkName).normalize();
            if (!source.startsWith(root)) throw new IOException("Runtime archive link escapes staging");
            links.add(new Link(target,source,symbolic)); return;
        }
        try (OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            byte[] buffer=new byte[65536]; int count;
            while ((count=input.read(buffer))!=-1) {
                check.run(); unpacked+=count;
                if (unpacked>MAX_UNPACKED) throw new IOException("Runtime archive exceeds unpacked size limit");
                output.write(buffer,0,count);
            }
        }
        if (executable && !target.toFile().setExecutable(true,false)) throw new IOException("Cannot set runtime executable permissions");
    }
    private void finishLinks() throws IOException {
        // Create only internal links after all regular entries have been written.
        for (var link:links) {
            check.run();
            if (!link.path.getParent().toRealPath().startsWith(root.toRealPath())) throw new IOException("Runtime link parent escapes staging");
            if (link.symbolic) Files.createSymbolicLink(link.path,link.path.getParent().relativize(link.target));
        }
        List<Link> hard=links.stream().filter(link -> !link.symbolic).toList();
        while (!hard.isEmpty()) {
            List<Link> pending=new ArrayList<>();
            for (var link:hard) {
                if (Files.isRegularFile(link.target)) Files.createLink(link.path,link.target);
                else pending.add(link);
            }
            if (pending.size()==hard.size()) throw new IOException("Unresolved runtime archive hard link");
            hard=pending;
        }
        for (var link:links) if (!link.path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Runtime archive link escapes staging");
    }
}
