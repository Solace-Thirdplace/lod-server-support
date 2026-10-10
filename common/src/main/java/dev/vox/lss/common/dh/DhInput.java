package dev.vox.lss.common.dh;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Small big-endian cursor. Every variable-length read is checked before copying. */
final class DhInput {
    final byte[] bytes;
    int offset;
    final int end;
    DhInput(byte[] bytes) { this(bytes, 0, bytes.length); }
    DhInput(byte[] bytes, int start, int length) { this.bytes = bytes; offset = start; end = start + length; }
    int remaining() { return end - offset; }
    void require(int count) throws DhCodecException {
        if (count < 0 || count > remaining()) throw malformed("Truncated DH field");
    }
    int u8() throws DhCodecException { require(1); return bytes[offset++] & 255; }
    boolean bool() throws DhCodecException {
        int value = u8();
        if (value > 1) throw malformed("Invalid boolean");
        return value == 1;
    }
    int u16() throws DhCodecException { return (u8() << 8) | u8(); }
    int i32() throws DhCodecException { return (u16() << 16) | u16(); }
    long i64() throws DhCodecException { return ((long) i32() << 32) | (i32() & 0xffffffffL); }
    byte[] take(int count) throws DhCodecException {
        require(count);
        byte[] result = java.util.Arrays.copyOfRange(bytes, offset, offset + count);
        offset += count;
        return result;
    }
    void exhausted() throws DhCodecException {
        if (remaining() != 0) throw malformed("Trailing DH bytes");
    }
    String string(DhProfile profile, int limit) throws DhCodecException {
        return string(profile, limit, u16());
    }
    String wideString(DhProfile profile, int limit) throws DhCodecException {
        return string(profile, limit, i32());
    }
    private String string(DhProfile profile, int limit, int units) throws DhCodecException {
        if (units < 0) throw malformed("Negative DH string length");
        int size = units;
        if (units > limit) throw limit("DH string exceeds limit");
        if (profile.support()) {
            // Count validated Unicode scalar values in UTF-16 units, without consuming
            // a following field or splitting the two units of a supplementary character.
            int cursor = offset, chars = 0;
            while (chars < units) {
                if (cursor >= end) throw malformed("Truncated UTF-8 string");
                int first = bytes[cursor] & 255;
                int width = utfWidth(first);
                if (width == 0 || width > end - cursor) throw malformed("Invalid UTF-8 string");
                chars += width == 4 ? 2 : 1;
                cursor += width;
                if (cursor - offset > limit) throw limit("DH string exceeds byte limit");
            }
            if (chars != units) throw malformed("UTF-16 prefix splits a surrogate pair");
            size = cursor - offset;
        }
        require(size);
        try {
            String result = utf8(bytes, offset, size);
            offset += size;
            return result;
        } catch (CharacterCodingException e) { throw malformed("Invalid UTF-8 string"); }
    }
    record Text(String value, int bytes) {}
    /** Support's mapping encoder writes UTF-16 units, followed by ordinary UTF-8. */
    static Text supportText(DataInputStream in, int units, int byteLimit) throws IOException {
        if (units < 0) throw malformed("Negative DH string length");
        if (units > byteLimit) throw limit("DH string exceeds byte limit");
        var bytes = new ByteArrayOutputStream(Math.min(units, 256));
        int chars = 0;
        while (chars < units) {
            int first = in.readUnsignedByte(), width = utfWidth(first);
            if (width == 0) throw malformed("Invalid UTF-8 string");
            if (width > byteLimit - bytes.size()) throw limit("DH string exceeds byte limit");
            chars += width == 4 ? 2 : 1;
            if (chars > units) throw malformed("UTF-16 prefix splits a surrogate pair");
            bytes.write(first);
            for (int i = 1; i < width; i++) bytes.write(in.readUnsignedByte());
        }
        byte[] encoded = bytes.toByteArray();
        try { return new Text(utf8(encoded, 0, encoded.length), encoded.length); }
        catch (CharacterCodingException e) { throw malformed("Invalid UTF-8 string"); }
    }
    private static int utfWidth(int first) {
        return first < 0x80 ? 1 : first >= 0xc2 && first <= 0xdf ? 2
                : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
    }
    private static String utf8(byte[] bytes, int offset, int size) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, size)).toString();
    }
    static DhCodecException malformed(String message) {
        return new DhCodecException(DhCodecException.Kind.MALFORMED, message);
    }
    static DhCodecException limit(String message) {
        return new DhCodecException(DhCodecException.Kind.RESOURCE_LIMIT, message);
    }
}
