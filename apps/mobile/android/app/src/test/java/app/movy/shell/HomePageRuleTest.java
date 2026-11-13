package app.movy.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class HomePageRuleTest {
    private static final Source ROOT = new Source("a", "A", "https://www.a.example/", Arrays.asList("a.example", "*.a.example"), 0, true);
    private static final Source DEEP = new Source("b", "B", "https://b.example/home/", Arrays.asList("b.example"), 0, true);

    @Test public void rootPaths() {
        assertTrue(HomePageRule.isHome(ROOT, "https://www.a.example/"));
        assertTrue(HomePageRule.isHome(ROOT, "https://www.a.example"));
        assertTrue(HomePageRule.isHome(ROOT, "https://a.example/?ref=1#top"));
    }

    @Test public void deeperIsNotHome() {
        assertFalse(HomePageRule.isHome(ROOT, "https://www.a.example/movie/123"));
        assertFalse(HomePageRule.isHome(ROOT, "https://www.a.example/search?q=x"));
    }

    @Test public void foreignHostIsNotHome() {
        assertFalse(HomePageRule.isHome(ROOT, "https://evil.example/"));
        assertFalse(HomePageRule.isHome(ROOT, "about:blank"));
        assertFalse(HomePageRule.isHome(ROOT, null));
    }

    @Test public void startUrlPathCountsAsHome() {
        assertTrue(HomePageRule.isHome(DEEP, "https://b.example/home"));
        assertTrue(HomePageRule.isHome(DEEP, "https://b.example/Home/?x=1"));
        assertTrue(HomePageRule.isHome(DEEP, "https://b.example/"));
        assertFalse(HomePageRule.isHome(DEEP, "https://b.example/home/sub"));
    }

    @Test public void anySourceMatches() {
        assertTrue(HomePageRule.isHomeOfAny(Arrays.asList(ROOT, DEEP), "https://b.example/"));
        assertFalse(HomePageRule.isHomeOfAny(Arrays.asList(ROOT, DEEP), "https://c.example/"));
    }

    @Test public void landingKeyIgnoresQueryHashAndTrailingSlash() {
        org.junit.Assert.assertEquals(HomePageRule.key("https://A.example/Browse/?x=1#y"), HomePageRule.key("https://a.example/browse"));
        assertTrue(HomePageRule.belongsTo(ROOT, "https://www.a.example/browse"));
        assertFalse(HomePageRule.belongsTo(ROOT, "https://b.example/browse"));
    }
}
