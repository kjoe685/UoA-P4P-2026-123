package engine.application;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Official v0.35.0 artifact identities, reviewed against GitHub release metadata. */
record OllamaPackage(String platform,String name,long bytes,String sha256,Format format,String executable,URI url) {
    enum Format { ZIP,GZIP_TAR,ZSTD_TAR }
    static final String VERSION="0.35.0";
    OllamaPackage {
        if (platform==null || !platform.matches("[a-z0-9-]+") || name==null || !name.matches("[A-Za-z0-9._-]+")
                || bytes<1 || bytes>3_000_000_000L || sha256==null || !sha256.matches("[0-9a-f]{64}") || format==null
                || executable==null || !java.util.Set.of("ollama.exe","ollama","bin/ollama").contains(executable)
                || url==null || url.getUserInfo()!=null || url.getQuery()!=null || url.getFragment()!=null)
            throw new IllegalArgumentException("Invalid pinned runtime package");
    }
    static OllamaPackage current() {
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT), arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm=arch.equals("aarch64") || arch.equals("arm64"), x64=arch.equals("amd64") || arch.equals("x86_64");
        if (!arm && !x64) throw new IllegalStateException("Managed Ollama requires an x64 or ARM64 platform");
        if (os.contains("win")) return arm
                ? official("windows-arm64","ollama-windows-arm64.zip",208072407,"99d061915a68fb563da0fb9316fd112cfc6fce0c9478601b2765b1f973cb715e",Format.ZIP,"ollama.exe")
                : official("windows-amd64","ollama-windows-amd64.zip",1461196158,"d6f7d3dd4f5d013553a78c1e78b2521fcf41d43dd2863e4596cdc046fe6036db",Format.ZIP,"ollama.exe");
        if (os.contains("mac")) return official("darwin-universal","ollama-darwin.tgz",160167937,
                "2608dbb0a0f0136a198db9d48b4f74ece55f452314a39452fca35b7cf20c2589",Format.GZIP_TAR,"ollama");
        if (os.contains("linux")) return arm
                ? official("linux-arm64","ollama-linux-arm64.tar.zst",1550231393,"cb627d332b1fe5055bd5485ca10d595da8429e447648209e375390ec3bd09374",Format.ZSTD_TAR,"bin/ollama")
                : official("linux-amd64","ollama-linux-amd64.tar.zst",1427765407,"1c114a6b220c5efca2ef2b1e5f01d1e535e26f6cd6d1678c8489325d2835e525",Format.ZSTD_TAR,"bin/ollama");
        throw new IllegalStateException("Managed Ollama supports Windows, Linux and macOS");
    }
    private static OllamaPackage official(String platform,String name,long bytes,String sha,Format format,String executable) {
        return new OllamaPackage(platform,name,bytes,sha,format,executable,URI.create("https://github.com/ollama/ollama/releases/download/v"+VERSION+"/"+name));
    }
    Map<String,Object> metadata() { return Map.of("version",VERSION,"platform",platform,"archive",name,"bytes",bytes,"sha256",sha256,"url",url); }
}
