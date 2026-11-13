package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DohTest {
    @Test
    public void resolverKeepsOnlyIpv4ARecordsAndMinTtl() throws IOException {
        String body = "{\"Status\":0,\"Answer\":["
                + "{\"name\":\"x.example\",\"type\":5,\"TTL\":99,\"data\":\"y.example.\"},"
                + "{\"name\":\"y.example\",\"type\":1,\"TTL\":30,\"data\":\"192.0.2.10\"},"
                + "{\"name\":\"y.example\",\"type\":1,\"TTL\":20,\"data\":\"192.0.2.11\"},"
                + "{\"name\":\"y.example\",\"type\":28,\"TTL\":10,\"data\":\"2001:db8::1\"}]}";
        DohResolver.Parsed p = DohResolver.parse(body);
        assertEquals(2, p.addrs.size());
        assertEquals("192.0.2.10", p.addrs.get(0).getHostAddress());
        assertEquals(20, p.ttlSeconds);
    }

    @Test
    public void resolverToleratesNoAnswerAndRejectsGarbage() throws IOException {
        assertTrue(DohResolver.parse("{\"Status\":3}").addrs.isEmpty());
        try {
            DohResolver.parse("not json");
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("doh bad json", expected.getMessage());
        }
    }

    @Test
    public void hostPatternsMatchExactAndWildcard() {
        List<String> p = Arrays.asList("api.example.org", "*.cdn.example.net");
        assertTrue(DohRescue.matches(p, "api.example.org"));
        assertTrue(DohRescue.matches(p, "API.Example.Org"));
        assertTrue(DohRescue.matches(p, "a.cdn.example.net"));
        assertFalse(DohRescue.matches(p, "example.org"));
        assertFalse(DohRescue.matches(p, "evilcdn.example.net"));
        assertFalse(DohRescue.matches(p, null));
        assertFalse(DohRescue.matches(Collections.emptyList(), "api.example.org"));
    }

    @Test
    public void requestDropsHopByHopAndCookieHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Origin", "https://site.example");
        h.put("Cookie", "secret=1");
        h.put("Accept-Encoding", "gzip");
        h.put("Connection", "keep-alive");
        h.put("X-Bad", "a\r\nInjected: 1");
        String req = DohProxy.buildRequest("GET", "api.example.org", "/3/x?a=1", h);
        assertTrue(req.startsWith("GET /3/x?a=1 HTTP/1.1\r\nHost: api.example.org\r\n"));
        assertTrue(req.contains("Accept: application/json\r\n"));
        assertTrue(req.contains("Origin: https://site.example\r\n"));
        assertFalse(req.contains("secret"));
        assertFalse(req.contains("gzip"));
        assertFalse(req.contains("Injected"));
        assertTrue(req.endsWith("\r\n\r\n"));
    }

    @Test
    public void parsesIdentityBody() throws IOException {
        byte[] raw = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"a\":1}")
                .getBytes(StandardCharsets.ISO_8859_1);
        DohProxy.Reply r = DohProxy.parse(raw, false);
        assertEquals(200, r.status);
        assertEquals("application/json", r.headers.get("Content-Type"));
        assertEquals("{\"a\":1}", new String(r.body, StandardCharsets.UTF_8));
    }

    @Test
    public void parsesChunkedBody() throws IOException {
        byte[] raw = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("Wikipedia", new String(DohProxy.parse(raw, false).body, StandardCharsets.UTF_8));
    }

    @Test
    public void headResponseHasNoBodyAndTruncatedChunkFails() throws IOException {
        byte[] head = "HTTP/1.1 204 No Content\r\nX: y\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(0, DohProxy.parse(head, true).body.length);
        byte[] bad = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nFF\r\nshort".getBytes(StandardCharsets.ISO_8859_1);
        try {
            DohProxy.parse(bad, false);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("short chunk", expected.getMessage());
        }
    }
}
