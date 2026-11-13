package app.movy.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import java.util.Base64;

import org.junit.Test;

public class ObfuscatorTest {
    @Test
    public void roundTripsAndHidesThePlainUrl() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 37 + 11);
        Obfuscator o = new Obfuscator(Base64.getEncoder().encodeToString(key));
        String[] urls = {"https://a.example.com/", "http://x.y/q?q=1", "https://z.example.net/a/b/c/d/e/f/g"};
        for (String url : urls) {
            String enc = o.encode(url);
            assertEquals(url, o.decode(enc));
            assertFalse(enc.contains("example"));
            assertNotEquals(url, enc);
        }
    }

    @Test
    public void matchesJavaBase64() {
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, (byte) 250};
        assertEquals(Base64.getEncoder().encodeToString(data), Obfuscator.base64Encode(data));
        assertArrayEquals(data, Obfuscator.base64Decode(Base64.getEncoder().encodeToString(data)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyKeyRejected() {
        new Obfuscator("");
    }
}
