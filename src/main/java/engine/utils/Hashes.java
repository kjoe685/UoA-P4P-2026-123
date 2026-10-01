package engine.utils;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Hashes {
    private Hashes() { }
    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
