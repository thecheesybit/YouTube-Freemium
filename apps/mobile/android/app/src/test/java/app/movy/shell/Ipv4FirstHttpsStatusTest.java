package app.movy.shell;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

public class Ipv4FirstHttpsStatusTest {
    private static int parse(String s) throws IOException {
        return Ipv4FirstHttps.parseStatusLine(new ByteArrayInputStream(s.getBytes()));
    }

    @Test public void parsesStatus() throws IOException {
        assertEquals(206, parse("HTTP/1.1 206 Partial Content\r\nX: y\r\n\r\n"));
        assertEquals(403, parse("HTTP/1.0 403 Forbidden\r\n"));
    }

    @Test(expected = IOException.class) public void rejectsGarbage() throws IOException {
        parse("nonsense\r\n");
    }
}
