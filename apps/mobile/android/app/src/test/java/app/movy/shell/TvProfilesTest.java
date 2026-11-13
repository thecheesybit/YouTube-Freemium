package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class TvProfilesTest {
    private static final Obfuscator OBF = new Obfuscator("AQIDBAUGBwgJCgsMDQ4PEA==");

    private static String defaults(String extra) {
        return "{\"recommendedSource\":\"a\",\"sources\":[{\"id\":\"a\",\"name\":\"Alpha\",\"urlEnc\":\"" + OBF.encode("https://a.example.com/")
                + "\",\"allowedHosts\":[\"a.example.com\",\"*.a.example.com\"]},{\"id\":\"b\",\"name\":\"Beta\",\"urlEnc\":\""
                + OBF.encode("https://b.example.org/") + "\",\"allowedHosts\":[\"b.example.org\"]}]" + extra + "}";
    }

    private static final String PROFILES = ",\"tvProfiles\":{\"a\":{\"notes\":\"x\",\"rows\":[\".r\"],\"skip\":[\".s\"],"
            + "\"player\":{\"fullscreenButton\":[\"#fs\"],\"depth\":1},\"viewportWidth\":1280}}";

    @Test public void defaultsCarryProfilesAndDropUnknownKeys() throws Exception {
        LiveConfig c = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        JSONObject a = new JSONObject(c.tvProfileFor("a"));
        assertEquals(".r", a.getJSONArray("rows").getString(0));
        assertEquals("#fs", a.getJSONObject("player").getJSONArray("fullscreenButton").getString(0));
        assertFalse("notes are for humans", a.has("notes"));
        assertEquals("{}", c.tvProfileFor("b"));
        assertEquals("{}", c.tvProfileFor(null));
    }

    @Test public void remoteOverridesPerSourceAndPerKey() throws Exception {
        LiveConfig base = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        LiveConfig next = LiveConfig.merge(base, "{\"tvProfiles\":{\"a\":{\"rows\":[\".new\"]},\"b\":{\"skip\":[\".b\"]}}}", UrlDecoder.IDENTITY);
        JSONObject a = new JSONObject(next.tvProfileFor("a"));
        assertEquals(".new", a.getJSONArray("rows").getString(0));
        assertEquals("untouched keys stay", ".s", a.getJSONArray("skip").getString(0));
        assertEquals(1280, a.getInt("viewportWidth"));
        assertEquals(".b", new JSONObject(next.tvProfileFor("b")).getJSONArray("skip").getString(0));
        // a remote without tvProfiles keeps the defaults
        LiveConfig same = LiveConfig.merge(base, "{\"tagline\":\"x\"}", UrlDecoder.IDENTITY);
        assertEquals(base.tvProfileFor("a"), same.tvProfileFor("a"));
    }

    private static void rejects(String profileJson) {
        try {
            LiveConfig.merge(LiveConfig.parseDefaults(defaults(""), OBF), "{\"tvProfiles\":" + profileJson + "}", UrlDecoder.IDENTITY);
            fail("expected rejection: " + profileJson);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void invalidProfilesAreRejected() {
        rejects("{\"Bad Id\":{}}");
        rejects("{\"a\":{\"rows\":\".r\"}}");
        rejects("{\"a\":{\"rows\":[1]}}");
        rejects("{\"a\":{\"rows\":[\"\"]}}");
        rejects("{\"a\":{\"viewportWidth\":100}}");
        rejects("{\"a\":{\"viewportWidth\":1280.5}}");
        rejects("{\"a\":{\"player\":{\"depth\":9}}}");
        rejects("{\"a\":{\"servers\":{\"toggle\":5}}}");
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 41; i++) big.append(i == 0 ? "" : ",").append("\".s").append(i).append('"');
        rejects("{\"a\":{\"skip\":" + big + "]}}");
    }

    @Test public void profileFollowsTheTopLevelUrlThenTheActiveSource() {
        LiveConfig c = LiveConfig.parseDefaults(defaults(PROFILES), OBF);
        Source b = c.find("b");
        assertTrue(TvBridge.profileFor(c, b, "https://www.a.example.com/movie/1").contains("\".r\""));
        assertEquals("{}", TvBridge.profileFor(c, b, "https://b.example.org/"));
        assertTrue("unknown url falls back to the active source", TvBridge.profileFor(c, c.find("a"), "https://other.example.net/").contains("\".r\""));
        assertEquals("{}", TvBridge.profileFor(c, null, ""));
    }
}
