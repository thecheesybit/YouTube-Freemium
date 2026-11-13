package app.movy.shell;

import java.nio.charset.StandardCharsets;

/**
 * XOR + base64 obfuscation of start URLs (build writes them, this decodes). Keeps URLs out of plain
 * strings in the APK; it is not encryption. Own base64 decoder because java.util.Base64 needs API 26.
 */
public final class Obfuscator implements UrlDecoder {
    private final byte[] key;

    public Obfuscator(String keyBase64) {
        this.key = base64Decode(keyBase64);
        if (key.length == 0) throw new IllegalArgumentException("empty obfuscation key");
    }

    @Override
    public String decode(String encoded) {
        byte[] data = base64Decode(encoded);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ key[i % key.length]);
        return new String(out, StandardCharsets.UTF_8);
    }

    /** Test/build helper: the inverse of {@link #decode}. */
    public String encode(String plain) {
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        byte[] x = new byte[data.length];
        for (int i = 0; i < data.length; i++) x[i] = (byte) (data[i] ^ key[i % key.length]);
        return base64Encode(x);
    }

    private static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    static byte[] base64Decode(String s) {
        String t = s.trim();
        int len = t.length();
        while (len > 0 && t.charAt(len - 1) == '=') len--;
        byte[] out = new byte[len * 6 / 8];
        int buf = 0, bits = 0, o = 0;
        for (int i = 0; i < len; i++) {
            int v = ALPHA.indexOf(t.charAt(i));
            if (v < 0) throw new IllegalArgumentException("bad base64");
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        return out;
    }

    static String base64Encode(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xFF : 0);
            sb.append(ALPHA.charAt(b >> 18 & 63)).append(ALPHA.charAt(b >> 12 & 63));
            sb.append(i + 1 < d.length ? ALPHA.charAt(b >> 6 & 63) : '=');
            sb.append(i + 2 < d.length ? ALPHA.charAt(b & 63) : '=');
        }
        return sb.toString();
    }
}
