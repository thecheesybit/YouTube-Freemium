package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

public class NavRulesTest {
    private static final String START = "https://movie.example/";
    private static final NavRules.Verdict ALLOW = NavRules.Verdict.ALLOW;
    private static final NavRules.Verdict KILL = NavRules.Verdict.KILL;

    private NavRules rules;

    @Before
    public void setUp() {
        rules = new NavRules(START, Arrays.asList("movie.example", "*.movie.example"), Collections.singletonList("sso.example.com"), "block");
    }

    private NavRules.Verdict nav(String url, boolean redirect, boolean gesture) {
        return rules.mainFrame(url, redirect, gesture, START);
    }

    @Test
    public void liveUpdateSwitchesAllowedHostsAndStartPages() {
        assertEquals(KILL, nav("https://other.example.org/x", false, true));
        rules.update(Arrays.asList("movie.example", "*.movie.example", "other.example.org"),
                new java.util.HashSet<>(Arrays.asList("movie.example", "other.example.org")), Collections.<String>emptyList());
        assertEquals(ALLOW, nav("https://other.example.org/x", false, true));
        rules.update(Collections.singletonList("other.example.org"),
                Collections.singleton("other.example.org"), Collections.<String>emptyList());
        assertEquals(KILL, nav("https://movie.example/movie/1", false, false));
        assertTrue(rules.isAllowedHost("other.example.org"));
        assertFalse(rules.isAllowedHost("movie.example"));
    }

    @Test
    public void liveUpdateKeepsBuiltinSignInHosts() {
        rules.update(Collections.singletonList("a.example.com"), Collections.singleton("a.example.com"),
                Collections.singletonList("sso2.example.com"));
        assertEquals(ALLOW, rules.mainFrame("https://accounts.google.com/o/oauth2", false, true, "https://a.example.com/"));
        assertEquals(ALLOW, rules.mainFrame("https://sso2.example.com/login", false, true, "https://a.example.com/"));
    }

    @Test
    public void allowedHostLoads() {
        assertEquals(ALLOW, nav("https://movie.example/movie/1", false, false));
        assertEquals(ALLOW, nav("https://cdn.movie.example/a", false, true));
    }

    @Test
    public void offHostIsKilledWithOrWithoutGesture() {
        assertEquals(KILL, nav("https://ads.example.net/x", false, false));
        assertEquals(KILL, nav("https://ads.example.net/x", false, true));
        assertEquals(KILL, nav("https://movie.example.evil.com/", false, true));
        assertEquals(KILL, nav("https://movie.example@evil.com/", false, true));
    }

    @Test
    public void mirrorRedirectFromStartPageIsFollowedAndRemembered() {
        assertEquals(ALLOW, rules.mainFrame("https://movy-new.example/", true, false, null));
        assertTrue(rules.isAllowedHost("movy-new.example"));
        assertEquals(ALLOW, nav("https://movy-new.example/movie/2", false, true));
    }

    @Test
    public void redirectOutOfNonStartPageIsKilled() {
        assertEquals(ALLOW, nav("https://movie.example/go/abc", false, true)); // click-tracker path, chain not trusted
        assertEquals(KILL, nav("https://ads.example.net/landing", true, false));
        assertFalse(rules.isAllowedHost("ads.example.net"));
    }

    @Test
    public void redirectAfterLeavingStartPageIsKilled() {
        nav("https://movie.example/", false, false);
        nav("https://movie.example/movie/1", false, true);
        assertEquals(KILL, nav("https://ads.example.net/", true, false));
    }

    @Test
    public void authHostNeedsGesture() {
        assertEquals(ALLOW, nav("https://accounts.google.com/o/oauth2/auth", false, true));
        assertEquals(KILL, new NavRules(START, Arrays.asList("movie.example"), null, "block")
                .mainFrame("https://accounts.google.com/o/oauth2/auth", false, false, START));
    }

    @Test
    public void authRedirectChainAndReturnWork() {
        assertEquals(ALLOW, nav("https://accounts.google.com/signin", false, true));
        assertEquals(ALLOW, rules.mainFrame("https://accounts.google.com/signin/v2", true, false, "https://accounts.google.com/signin"));
        assertEquals(ALLOW, rules.mainFrame("https://movie.example/callback", true, false, "https://accounts.google.com/x"));
    }

    @Test
    public void authPathPrefixAndConfigHosts() {
        assertEquals(ALLOW, nav("https://www.facebook.com/login/dialog", false, true));
        assertEquals(KILL, nav("https://www.facebook.com/somepage", false, true));
        assertEquals(ALLOW, nav("https://discord.com/oauth2/authorize", false, true));
        assertEquals(KILL, nav("https://discord.com/channels/1", false, true));
        assertEquals(ALLOW, nav("https://sso.example.com/login", false, true));
    }

    @Test
    public void otherAppSchemesAreAlwaysKilled() {
        String[] urls = {
            "intent://x.com/#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fads.example%2F;end",
            "market://details?id=a.b", "tel:123", "mailto:a@b.c", "sms:1", "whatsapp://send?text=x",
            "customscheme://open", "data:text/html,<script>1</script>", "file:///sdcard/x.html",
        };
        for (String u : urls) {
            assertEquals(u, KILL, nav(u, false, true));
            assertEquals(u, KILL, nav(u, true, true));
        }
    }

    @Test
    public void inertSchemes() {
        assertEquals(ALLOW, nav("about:blank", false, false));
        assertEquals(KILL, nav("about:config", false, false));
        assertEquals(ALLOW, nav("javascript:void(0)", false, false));
    }

    @Test
    public void inAppModeLoadsAnyWebPageButStillKillsSchemes() {
        NavRules r = new NavRules(START, Arrays.asList("movie.example"), null, "in-app");
        assertEquals(ALLOW, r.mainFrame("https://other.example/", false, false, START));
        assertEquals(KILL, r.mainFrame("market://x", false, true, START));
    }

    @Test
    public void popupToSameHostNeedsGesture() {
        assertTrue(rules.popupOpensInMain("https://movie.example/movie/3", true, false));
        assertFalse(rules.popupOpensInMain("https://movie.example/movie/3", false, false));
    }

    @Test
    public void popupToAdHostOrEngineHitIsKilled() {
        assertFalse(rules.popupOpensInMain("https://www.91mobiles.com/x", true, false));
        assertFalse(rules.popupOpensInMain("https://movie.example/ad", true, true));
        assertFalse(rules.popupOpensInMain("https://accounts.google.com/", true, false));
        assertFalse(rules.popupOpensInMain("intent://x#Intent;end", true, false));
    }

    @Test
    public void subframesAreNotHostRestricted() {
        assertTrue(rules.subframeAllowed("https://player.cdn-host.example/embed/1"));
        assertTrue(rules.subframeAllowed("about:blank"));
        assertFalse(rules.subframeAllowed("intent://x#Intent;end"));
        assertFalse(rules.subframeAllowed("market://x"));
    }

    @Test
    public void killLogHidesHostsInRelease() {
        String s = KillLog.describe("https://secret-ads.example/path?q=1", false);
        assertTrue(s.matches("https://[0-9a-f]{8}"));
        assertEquals("tel:", KillLog.describe("tel:123", false));
        assertEquals("https://secret-ads.example/p", KillLog.describe("https://secret-ads.example/p", true));
    }
}
