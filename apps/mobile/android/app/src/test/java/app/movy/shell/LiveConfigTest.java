package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LiveConfigTest {
    private static final Obfuscator OBF = new Obfuscator("AQIDBAUGBwgJCgsMDQ4PEA==");

    private static String defaultsJson() {
        return "{\"contentVersion\":0,\"recommendedSource\":\"a\",\"tagline\":\"Tag\",\"credit\":\"Me\","
                + "\"sources\":[{\"id\":\"a\",\"name\":\"Alpha\",\"urlEnc\":\"" + OBF.encode("https://a.example.com/")
                + "\",\"allowedHosts\":[\"a.example.com\",\"*.a.example.com\"],\"accent\":\"#E50914\"},"
                + "{\"id\":\"b\",\"name\":\"Beta\",\"urlEnc\":\"" + OBF.encode("https://b.example.org/")
                + "\",\"allowedHosts\":[\"b.example.org\"]}]}";
    }

    private static LiveConfig base() {
        return LiveConfig.parseDefaults(defaultsJson(), OBF);
    }

    private static void rejects(String json) {
        try {
            LiveConfig.merge(base(), json, UrlDecoder.IDENTITY);
            fail("expected rejection: " + json);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void defaultsDecodeObfuscatedUrls() {
        LiveConfig c = base();
        assertEquals(2, c.sources.size());
        assertEquals("https://a.example.com/", c.sources.get(0).url);
        assertEquals(0xFFE50914, c.sources.get(0).accent);
        assertEquals(LiveConfig.PALETTE[1], c.sources.get(1).accent);
        assertEquals("Tag", c.tagline);
        assertEquals("a", c.recommended().id);
        assertEquals("Choose your source", c.chooserTitle);
    }

    @Test
    public void remoteOverridesPerFieldAndKeepsTheRest() {
        LiveConfig c = LiveConfig.merge(base(),
                "{\"contentVersion\":7,\"chooser\":{\"subtitle\":\"Hello\"},\"splash\":{\"tagline\":\"New\",\"minMs\":300}}",
                UrlDecoder.IDENTITY);
        assertEquals(7, c.contentVersion);
        assertEquals("Hello", c.chooserSubtitle);
        assertEquals("New", c.tagline);
        assertEquals(300, c.splashMinMs);
        assertEquals(2, c.sources.size());
        assertEquals("Choose your source", c.chooserTitle);
    }

    @Test
    public void remoteReplacesSourceListAndUsesPlainUrls() {
        LiveConfig c = LiveConfig.merge(base(),
                "{\"sources\":[{\"id\":\"z\",\"name\":\"Zed\",\"url\":\"https://z.example.net/x\"}],\"recommendedSource\":\"z\"}",
                UrlDecoder.IDENTITY);
        assertEquals(1, c.sources.size());
        assertEquals(java.util.Arrays.asList("z.example.net", "*.z.example.net"), c.sources.get(0).allowedHosts);
        assertEquals("z", c.recommended().id);
    }

    @Test
    public void disabledSourcesAreHiddenAndExcludedFromHosts() {
        LiveConfig c = LiveConfig.merge(base(),
                "{\"sources\":[{\"id\":\"a\",\"name\":\"A\",\"url\":\"https://a.example.com/\"},"
                        + "{\"id\":\"b\",\"name\":\"B\",\"url\":\"https://b.example.org/\",\"enabled\":false}],"
                        + "\"extraAllowedHosts\":[\"cdn.example.net\"]}", UrlDecoder.IDENTITY);
        assertEquals(1, c.enabledSources().size());
        List<String> hosts = c.allAllowedHosts();
        assertTrue(hosts.contains("a.example.com"));
        assertFalse(hosts.contains("b.example.org"));
        assertTrue(hosts.contains("cdn.example.net"));
        assertTrue(c.startHosts().contains("a.example.com"));
        assertFalse(c.startHosts().contains("b.example.org"));
        assertNull(c.find("nope"));
    }

    @Test
    public void recommendedFallsBackToFirstEnabled() {
        LiveConfig c = LiveConfig.merge(base(),
                "{\"sources\":[{\"id\":\"a\",\"name\":\"A\",\"url\":\"https://a.example.com/\",\"enabled\":false},"
                        + "{\"id\":\"b\",\"name\":\"B\",\"url\":\"https://b.example.org/\"}],\"recommendedSource\":\"a\"}",
                UrlDecoder.IDENTITY);
        assertEquals("b", c.recommended().id);
    }

    @Test
    public void announcementMinBuildAndIsland() {
        LiveConfig c = LiveConfig.merge(base(),
                "{\"announcement\":{\"id\":\"n1\",\"text\":\"Hi\",\"level\":\"warn\"},\"minBuild\":9,"
                        + "\"updateMessage\":\"Please update\",\"island\":{\"visibility\":\"always\"}}", UrlDecoder.IDENTITY);
        assertEquals("n1", c.announcement.id);
        assertEquals("warn", c.announcement.level);
        assertEquals(9, c.minBuild);
        assertEquals("always", c.islandVisibility);
        LiveConfig cleared = LiveConfig.merge(c, "{\"announcement\":null}", UrlDecoder.IDENTITY);
        assertNull(cleared.announcement);
    }

    @Test
    public void invalidInputIsRejected() {
        rejects("not json");
        rejects("[]");
        rejects("{\"sources\":[]}");
        rejects("{\"sources\":[{\"id\":\"A B\",\"name\":\"x\",\"url\":\"https://x.example.com\"}]}");
        rejects("{\"sources\":[{\"id\":\"a\",\"name\":\"x\",\"url\":\"ftp://x.example.com\"}]}");
        rejects("{\"sources\":[{\"id\":\"a\",\"name\":\"x\",\"url\":\"https://x.example.com\"},"
                + "{\"id\":\"a\",\"name\":\"y\",\"url\":\"https://y.example.com\"}]}");
        rejects("{\"sources\":[{\"id\":\"a\",\"name\":\"x\",\"url\":\"https://x.example.com\",\"accent\":\"red\"}]}");
        rejects("{\"sources\":[{\"id\":\"a\",\"name\":\"x\",\"url\":\"https://x.example.com\",\"enabled\":false}]}");
        rejects("{\"recommendedSource\":\"missing\"}");
        rejects("{\"island\":{\"visibility\":\"never\"}}");
        rejects("{\"contentVersion\":-1}");
        rejects("{\"contentVersion\":\"3\"}");
        rejects("{\"extraAllowedHosts\":[\"http://x.com\"]}");
        rejects("{\"tagline\":5}");
    }

    @Test
    public void badConfigLeavesBaseUntouched() {
        LiveConfig b = base();
        try {
            LiveConfig.merge(b, "{\"contentVersion\":3,\"island\":{\"visibility\":\"x\"}}", UrlDecoder.IDENTITY);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals(0, b.contentVersion);
        }
    }

    @Test
    public void applierAppliesValidAndIgnoresInvalid() {
        final LiveConfig[] sink = new LiveConfig[1];
        final List<String> saved = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final LiveConfig b = base();
        RemoteApplier a = new RemoteApplier(b, () -> sink[0] != null ? sink[0] : b, v -> sink[0] = v, saved::add, logs::add);
        assertFalse(a.apply(null));
        assertFalse(a.apply("  "));
        assertTrue(logs.isEmpty());
        assertFalse(a.apply("{\"island\":{\"visibility\":\"x\"}}"));
        assertEquals(1, logs.size());
        assertNull(sink[0]);
        assertTrue(saved.isEmpty());
        assertTrue(a.apply("{\"contentVersion\":2}"));
        assertEquals(2, sink[0].contentVersion);
        assertEquals(1, saved.size());
        // each remote JSON applies over the defaults, so a removed field reverts
        assertTrue(a.apply("{\"tagline\":\"X\"}"));
        assertEquals(0, sink[0].contentVersion);
        assertEquals("X", sink[0].tagline);
    }

    @Test
    public void dohHostsMergeAndValidate() {
        assertTrue(base().dohHosts.isEmpty());
        LiveConfig c = LiveConfig.merge(base(), "{\"dohHosts\":[\"Api.Example.org\",\"*.cdn.example.net\"]}", UrlDecoder.IDENTITY);
        assertEquals(java.util.Arrays.asList("api.example.org", "*.cdn.example.net"), c.dohHosts);
        LiveConfig kept = LiveConfig.merge(c, "{\"tagline\":\"x\"}", UrlDecoder.IDENTITY);
        assertEquals(c.dohHosts, kept.dohHosts);
        rejects("{\"dohHosts\":[\"https://api.example.org/\"]}");
    }
}
