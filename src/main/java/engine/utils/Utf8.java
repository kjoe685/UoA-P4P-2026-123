package engine.utils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Preserve exact scalar text; malformed bytes or surrogate candidates must never be replaced. */
public final class Utf8 {
    private Utf8() { }
    public static String decode(byte[] data) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
    }
    public static byte[] encode(String text) throws CharacterCodingException {
        ByteBuffer buffer=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
    }
}
